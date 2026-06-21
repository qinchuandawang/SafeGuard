"""
FP16 vs FP32 推理基准测试
对比半精度和全精度推理的延迟、吞吐、精度差异
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import torch
import time
import numpy as np
from models.xception import xception

DEVICE = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
MODEL_PATH = 'checkpoints/best_model.pth'
WARMUP_ITERS = 10
BENCH_ITERS = 100


def load_model(fp16=False):
    model = xception(num_classes=2)
    if os.path.exists(MODEL_PATH):
        cp = torch.load(MODEL_PATH, map_location=DEVICE)
        state_dict = cp['model_state_dict']
        # 兼容旧 checkpoint
        if any(k.startswith("block4.") for k in state_dict):
            block_map = {f"block{i}": f"middle_blocks.{i-4}" for i in range(4, 12)}
            new_state = {}
            for key, value in state_dict.items():
                new_key = key
                for old_b, new_b in block_map.items():
                    if key.startswith(old_b + "."):
                        new_key = key.replace(old_b + ".", new_b + ".", 1)
                        break
                new_state[new_key] = value
            state_dict = new_state
        model.load_state_dict(state_dict)
    model.to(DEVICE)
    model.eval()
    if fp16 and DEVICE.type == 'cuda':
        model = model.half()
    return model


def measure_latency(model, input_tensor, warmup=10, iters=100):
    """测量单张图片推理延迟"""
    # 预热
    with torch.no_grad():
        for _ in range(warmup):
            _ = model(input_tensor)
    if DEVICE.type == 'cuda':
        torch.cuda.synchronize()

    # 正式测试
    times = []
    with torch.no_grad():
        for _ in range(iters):
            if DEVICE.type == 'cuda':
                torch.cuda.synchronize()
            start = time.perf_counter()
            _ = model(input_tensor)
            if DEVICE.type == 'cuda':
                torch.cuda.synchronize()
            elapsed = (time.perf_counter() - start) * 1000
            times.append(elapsed)

    times = np.array(times)
    return {
        'mean_ms': float(np.mean(times)),
        'std_ms': float(np.std(times)),
        'min_ms': float(np.min(times)),
        'max_ms': float(np.max(times)),
        'p50_ms': float(np.percentile(times, 50)),
        'p95_ms': float(np.percentile(times, 95)),
        'p99_ms': float(np.percentile(times, 99)),
    }


def measure_throughput(model, input_tensor, batch_sizes, warmup=5, iters=50):
    """测量不同 batch size 的吞吐量"""
    results = {}
    for bs in batch_sizes:
        batch = input_tensor.expand(bs, -1, -1, -1)
        with torch.no_grad():
            for _ in range(warmup):
                _ = model(batch)
        if DEVICE.type == 'cuda':
            torch.cuda.synchronize()

        times = []
        with torch.no_grad():
            for _ in range(iters):
                if DEVICE.type == 'cuda':
                    torch.cuda.synchronize()
                start = time.perf_counter()
                _ = model(batch)
                if DEVICE.type == 'cuda':
                    torch.cuda.synchronize()
                elapsed = (time.perf_counter() - start) * 1000
                times.append(elapsed)

        mean_time = np.mean(times)
        results[bs] = {
            'latency_ms': float(mean_time),
            'throughput_imgs_per_sec': float(bs / (mean_time / 1000)),
        }
    return results


def measure_precision_difference(model_fp32, model_fp16, input_tensor):
    """测量 FP32 和 FP16 输出的精度差异"""
    with torch.no_grad():
        out_fp32 = model_fp32(input_tensor)
        out_fp16 = model_fp16(input_tensor.half())

    probs_fp32 = torch.softmax(out_fp32.float(), dim=1)
    probs_fp16 = torch.softmax(out_fp16.float(), dim=1)

    diff = (probs_fp32 - probs_fp16).abs()
    return {
        'max_prob_diff': float(diff.max()),
        'mean_prob_diff': float(diff.mean()),
        'pred_agreement': float((probs_fp32.argmax(dim=1) == probs_fp16.argmax(dim=1)).float().mean()),
    }


def measure_memory(model, input_tensor):
    """测量 GPU 显存占用"""
    if DEVICE.type != 'cuda':
        return {'allocated_mb': 0, 'reserved_mb': 0}

    torch.cuda.empty_cache()
    torch.cuda.reset_peak_memory_stats()

    with torch.no_grad():
        _ = model(input_tensor)

    return {
        'allocated_mb': float(torch.cuda.max_memory_allocated() / 1024 / 1024),
        'reserved_mb': float(torch.cuda.max_memory_reserved() / 1024 / 1024),
    }


def main():
    print("=" * 65)
    print("  XceptionNet 推理基准测试")
    print(f"  设备: {DEVICE}")
    print(f"  模型: {MODEL_PATH}")
    print("=" * 65)

    # 加载模型
    print("\n[1/4] 加载模型...")
    model_fp32 = load_model(fp16=False)
    model_fp16 = load_model(fp16=True) if DEVICE.type == 'cuda' else model_fp32

    params = sum(p.numel() for p in model_fp32.parameters())
    print(f"  参数量: {params:,}")

    # 创建测试输入
    dummy = torch.randn(1, 3, 299, 299, device=DEVICE)

    # 延迟测试
    print("\n[2/4] 单张推理延迟测试 (warmup={}, iters={})...".format(WARMUP_ITERS, BENCH_ITERS))
    lat_fp32 = measure_latency(model_fp32, dummy, WARMUP_ITERS, BENCH_ITERS)
    print(f"  FP32: {lat_fp32['mean_ms']:.2f}ms (P95={lat_fp32['p95_ms']:.2f}ms)")

    if DEVICE.type == 'cuda':
        lat_fp16 = measure_latency(model_fp16, dummy.half(), WARMUP_ITERS, BENCH_ITERS)
        speedup = lat_fp32['mean_ms'] / lat_fp16['mean_ms']
        print(f"  FP16: {lat_fp16['mean_ms']:.2f}ms (P95={lat_fp16['p95_ms']:.2f}ms)")
        print(f"  >>> FP16 加速比: {speedup:.2f}x")

    # 吞吐量测试
    print("\n[3/4] 批量推理吞吐量测试...")
    batch_sizes = [1, 4, 8, 16, 32]
    tp_fp32 = measure_throughput(model_fp32, dummy, batch_sizes)
    print(f"  {'Batch':>6}  {'FP32延迟':>10}  {'FP32吞吐':>12}", end="")
    if DEVICE.type == 'cuda':
        tp_fp16 = measure_throughput(model_fp16, dummy.half(), batch_sizes)
        print(f"  {'FP16延迟':>10}  {'FP16吞吐':>12}  {'加速比':>8}")
    else:
        print()
    print(f"  {'-'*6}  {'-'*10}  {'-'*12}", end="")
    if DEVICE.type == 'cuda':
        print(f"  {'-'*10}  {'-'*12}  {'-'*8}")
    else:
        print()

    for bs in batch_sizes:
        line = f"  {bs:>6}  {tp_fp32[bs]['latency_ms']:>8.1f}ms  {tp_fp32[bs]['throughput_imgs_per_sec']:>10.1f} img/s"
        if DEVICE.type == 'cuda':
            bs_speedup = tp_fp32[bs]['latency_ms'] / tp_fp16[bs]['latency_ms']
            line += f"  {tp_fp16[bs]['latency_ms']:>8.1f}ms  {tp_fp16[bs]['throughput_imgs_per_sec']:>10.1f} img/s  {bs_speedup:>6.2f}x"
        print(line)

    # 精度对比
    if DEVICE.type == 'cuda':
        print("\n[4/4] FP32 vs FP16 精度对比...")
        prec = measure_precision_difference(model_fp32, model_fp16, dummy)
        print(f"  最大概率差: {prec['max_prob_diff']:.6f}")
        print(f"  平均概率差: {prec['mean_prob_diff']:.6f}")
        print(f"  预测一致性: {prec['pred_agreement']*100:.1f}%")

    # 显存
    if DEVICE.type == 'cuda':
        print("\n--- 显存占用 ---")
        mem_fp32 = measure_memory(model_fp32, dummy)
        mem_fp16 = measure_memory(model_fp16, dummy.half())
        print(f"  FP32: {mem_fp32['allocated_mb']:.1f} MB")
        print(f"  FP16: {mem_fp16['allocated_mb']:.1f} MB")
        print(f"  显存节省: {(1 - mem_fp16['allocated_mb']/mem_fp32['allocated_mb'])*100:.1f}%")

    print("\n" + "=" * 65)
    print("  基准测试完成")
    print("=" * 65)


if __name__ == '__main__':
    main()
