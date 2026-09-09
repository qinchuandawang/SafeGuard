// 查询 llm_usage_record 账本：今日汇总 + 可选时间窗明细
const mysql = require('mysql2/promise');

(async () => {
  const conn = await mysql.createConnection({
    host: '127.0.0.1', port: 3306, user: 'root', password: '',
    database: 'safeguard', charset: 'utf8mb4',
  });

  const [today] = await conn.query(
    "SELECT COUNT(*) AS rows_cnt, COALESCE(SUM(total_tokens),0) AS tokens, " +
    "SUM(cache_hit = 1) AS cache_hit_rows FROM llm_usage_record WHERE created_at >= CURDATE()");
  console.log('TODAY:', JSON.stringify(today[0]));

  if (process.argv[2] && process.argv[3]) {
    const [win] = await conn.query(
      "SELECT id, request_id, scene, prompt_tokens, completion_tokens, total_tokens, " +
      "cache_hit, status, created_at FROM llm_usage_record " +
      "WHERE created_at >= ? AND created_at <= ? ORDER BY id ASC",
      [process.argv[2], process.argv[3]]);
    const rows = win;
    const out = {
      rows_total: rows.length,
      rows: rows.map(r => ({
        id: r.id, scene: r.scene, prompt_tokens: r.prompt_tokens,
        completion_tokens: r.completion_tokens, total_tokens: r.total_tokens,
        cache_hit: !!r.cache_hit, status: r.status, created_at: r.created_at,
      })),
      by_scene: rows.reduce((acc, r) => {
        const k = r.scene;
        acc[k] = acc[k] || { rows: 0, tokens: 0 };
        acc[k].rows += 1;
        acc[k].tokens += Number(r.total_tokens || 0);
        return acc;
      }, {}),
      window: [process.argv[2], process.argv[3]],
    };
    require('fs').writeFileSync(process.argv[4] || 'ledger-out.json',
      JSON.stringify(out, null, 2), 'utf8');
    console.log('WINDOW:', JSON.stringify(out, null, 2));
  }

  await conn.end();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
