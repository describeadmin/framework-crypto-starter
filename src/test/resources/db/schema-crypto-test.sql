CREATE TABLE IF NOT EXISTS crypto_test_entity (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  id_card      VARCHAR(255) NULL,
  id_card_idx  VARCHAR(64)  NULL,
  other_field  VARCHAR(64)  NULL,
  PRIMARY KEY (id),
  KEY idx_id_card_idx (id_card_idx)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
