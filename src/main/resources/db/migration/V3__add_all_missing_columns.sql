-- poker_user
ALTER TABLE poker_user ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP(6);
ALTER TABLE poker_user ADD COLUMN IF NOT EXISTS phone_number VARCHAR(20);
ALTER TABLE poker_user ADD COLUMN IF NOT EXISTS verified BOOLEAN DEFAULT false;

-- poker_user_balance
ALTER TABLE poker_user_balance ADD COLUMN IF NOT EXISTS bonus_balance INTEGER NOT NULL DEFAULT 0;

-- poker_deposit
ALTER TABLE poker_deposit ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE poker_deposit ADD COLUMN IF NOT EXISTS denied_reason TEXT;
ALTER TABLE poker_deposit ADD COLUMN IF NOT EXISTS approved_date TIMESTAMP WITH TIME ZONE;
ALTER TABLE poker_deposit ADD COLUMN IF NOT EXISTS approved_by INTEGER;

-- poker_withdrawal
ALTER TABLE poker_withdrawal ADD COLUMN IF NOT EXISTS approved_by INTEGER;
ALTER TABLE poker_withdrawal ADD COLUMN IF NOT EXISTS approve_date TIMESTAMP WITH TIME ZONE;
ALTER TABLE poker_withdrawal ADD COLUMN IF NOT EXISTS details JSONB;
ALTER TABLE poker_withdrawal ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'PENDING';

-- poker_outcome
ALTER TABLE poker_outcome ADD COLUMN IF NOT EXISTS is_accounted BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE poker_outcome ADD COLUMN IF NOT EXISTS account_date TIMESTAMP WITH TIME ZONE;
ALTER TABLE poker_outcome ADD COLUMN IF NOT EXISTS type VARCHAR;

-- poker_game_session
ALTER TABLE poker_game_session ADD COLUMN IF NOT EXISTS details JSONB NOT NULL DEFAULT '{}';
ALTER TABLE poker_game_session ADD COLUMN IF NOT EXISTS create_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE poker_game_session ADD COLUMN IF NOT EXISTS session_id VARCHAR NOT NULL;

-- poker_hand_history
ALTER TABLE poker_hand_history ADD COLUMN IF NOT EXISTS winning_hole_cards JSONB;
ALTER TABLE poker_hand_history ADD COLUMN IF NOT EXISTS winning_community_cards JSONB;
ALTER TABLE poker_hand_history ADD COLUMN IF NOT EXISTS winnings INTEGER;
ALTER TABLE poker_hand_history ADD COLUMN IF NOT EXISTS winner_username VARCHAR(255);
