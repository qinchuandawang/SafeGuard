DROP TABLE IF EXISTS `consumed_message`;
DROP TABLE IF EXISTS `detection_record_archive`;
ALTER TABLE `async_task`
    DROP INDEX `uk_idempotency_key`,
    DROP COLUMN `version`,
    DROP COLUMN `storage_tier`,
    DROP COLUMN `object_key`,
    DROP COLUMN `model_id`,
    DROP COLUMN `idempotency_key`,
    DROP COLUMN `file_hash`;
