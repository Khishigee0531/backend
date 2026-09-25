-- Initialize database with default data
-- This runs automatically on first PostgreSQL container startup

-- Create admin user (password: 123, BCrypt hash with 12 rounds)
INSERT INTO poker_user (username, email, password, role, verified)
VALUES ('admin', 'admin@manestack.dev', '$2a$12$2NaoCBGuNH43OEa8NHbIBei4jpAgESleC8kOEFjzOlQ3Yu5tOojvK', 'ADMIN', true)
ON CONFLICT (username) DO UPDATE SET password = EXCLUDED.password, role = EXCLUDED.role, verified = EXCLUDED.verified;

-- Create admin user balance
INSERT INTO poker_user_balance (user_id, balance, locked_amount, bonus_balance)
SELECT user_id, 1000000, 0, 0 FROM poker_user WHERE username = 'admin'
ON CONFLICT (user_id) DO NOTHING;

-- Create default poker table
INSERT INTO poker_table (table_name, max_players, variant, created_at, created_by, small_blind, big_blind, min_buy_in, max_buy_in, secure_id, rake_percent, card_bg_color, total_rake_collected)
VALUES ('Main Table', 9, 'TEXAS_HOLDEM', NOW(), 1, 10, 20, 1000, 10000, 'table-001', 0.05, '#FFFFFF', 0)
ON CONFLICT DO NOTHING;

-- Initialize jackpot
INSERT INTO poker_jackpot (amount, seed_amount, current_amount)
VALUES (0, 1000.00, 0.00)
ON CONFLICT DO NOTHING;