CREATE TABLE IF NOT EXISTS game_records (
  game TEXT PRIMARY KEY CHECK (game IN ('air', 'piano', 'runner')),
  score INTEGER NOT NULL CHECK (score > 0 AND score <= 2147483647),
  owner TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
