-- Performance indexes for common query patterns

-- Deposit queries
CREATE INDEX IF NOT EXISTS idx_poker_deposit_user_id ON poker_deposit(user_id);
CREATE INDEX IF NOT EXISTS idx_poker_deposit_status ON poker_deposit(status);
CREATE INDEX IF NOT EXISTS idx_poker_deposit_create_date ON poker_deposit(create_date);

-- Withdrawal queries
CREATE INDEX IF NOT EXISTS idx_poker_withdrawal_user_id ON poker_withdrawal(user_id);
CREATE INDEX IF NOT EXISTS idx_poker_withdrawal_status ON poker_withdrawal(status);

-- Outcome queries
CREATE INDEX IF NOT EXISTS idx_poker_outcome_user_id ON poker_outcome(user_id);
CREATE INDEX IF NOT EXISTS idx_poker_outcome_is_accounted ON poker_outcome(is_accounted);

-- Game session queries
CREATE INDEX IF NOT EXISTS idx_poker_game_session_table_id ON poker_game_session(table_id);

-- Hand history queries
CREATE INDEX IF NOT EXISTS idx_poker_hand_history_table_id ON poker_hand_history(table_id);

-- Chat message queries
CREATE INDEX IF NOT EXISTS idx_poker_chat_message_table_id ON poker_chat_message(table_id);

-- User search optimization
CREATE INDEX IF NOT EXISTS idx_poker_user_username_lower ON poker_user(LOWER(username));
