-- 情侣相册 + 情侣任务 + 小游戏 + 情侣日记 · D1 建表脚本
-- 执行：npx wrangler d1 execute qlapp-db --file=./schema.sql --remote
--
-- 只有两个人用，所以没有用户表、没有空间表：
-- 所有数据都在下面这几张表里，谁做什么靠 created_by / uploaded_by / device_id 字段记。
-- 全是 CREATE TABLE IF NOT EXISTS，重复执行安全，不会动已有数据。

-- 情侣任务
CREATE TABLE IF NOT EXISTS tasks (
  id          TEXT PRIMARY KEY,
  title       TEXT NOT NULL,
  note        TEXT NOT NULL DEFAULT '',
  done        INTEGER NOT NULL DEFAULT 0,
  done_by     TEXT,
  done_at     INTEGER,
  created_by  TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_created ON tasks(created_at DESC);

-- 情侣相册：真实图片存在 KV，这里只存索引（字段名 r2_key 是历史遗留，没改）
CREATE TABLE IF NOT EXISTS photos (
  id           TEXT PRIMARY KEY,
  r2_key       TEXT NOT NULL,
  caption      TEXT NOT NULL DEFAULT '',
  uploaded_by  TEXT NOT NULL,
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_photos_created ON photos(created_at DESC);

-- 三款游戏共享的最高纪录，平分保留原创造者。
CREATE TABLE IF NOT EXISTS game_records (
  game TEXT PRIMARY KEY CHECK (game IN ('air', 'piano', 'runner')),
  score INTEGER NOT NULL CHECK (score > 0 AND score <= 2147483647),
  owner TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

-- 情侣日记：一天两个人各一条，所以主键是「日期 + 作者名」而不是单独一个 id。
-- 同一天同一个人再写就走 ON CONFLICT 覆盖，等于编辑，天然保证「每人每天最多一条」。
--
-- ⚠️ 归属看的是 author（身份名：小江 / 甜甜），**不是 device_id**。
-- 曾经用 device_id 判归属，出过一次事故：一台手机上切换过身份（重装后会重新弹选身份页），
-- 两个人的内容就全落到同一个 device_id 上，界面上统统显示成「我」；更糟的是同一天两人
-- 在同一台手机写会撞主键互相覆盖。身份是「人」的属性，不是「设备」的属性。
-- device_id 现在只是「这条写在哪儿」的记录，不参与任何判断。
CREATE TABLE IF NOT EXISTS diary (
  entry_date TEXT NOT NULL,
  author     TEXT NOT NULL,
  mood       TEXT NOT NULL CHECK (mood IN ('happy', 'miss', 'calm', 'sad', 'angry')),
  content    TEXT NOT NULL DEFAULT '',
  device_id  TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (entry_date, author)
);
CREATE INDEX IF NOT EXISTS idx_diary_date ON diary(entry_date DESC);

-- 留言墙：便签形态，只能往前加，不能改也不能删（产品上就是这么定的）。
-- 所以这张表只有 INSERT，没有 UPDATE/DELETE。
-- 归属同样看 author（身份名），device_id 只记「这条写在哪儿」。
CREATE TABLE IF NOT EXISTS messages (
  id         TEXT PRIMARY KEY,
  device_id  TEXT NOT NULL,
  author     TEXT NOT NULL DEFAULT '',
  content    TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(created_at DESC);

-- 每台设备上次看留言墙的时间，用来算未读。
-- 单独一张表而不是写在 messages 里：已读是「人」的属性，不是「留言」的属性，
-- 给每条留言存两个已读标志在后面加人时会很别扭。
CREATE TABLE IF NOT EXISTS wall_reads (
  device_id TEXT PRIMARY KEY,
  read_at   INTEGER NOT NULL
);

-- 两个人的共同设置，键值对形态。
--
-- 目前存 nickname（昵称）和 anniversary（纪念日）。
-- 放后端是为了让管理网页也能改——它们原本只在手机 SharedPreferences 里，
-- 网页够不着。表结构故意做成通用的 key-value，以后再加设置项不用改表。
CREATE TABLE IF NOT EXISTS settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- 五子棋对局：一局一行，两个人轮流落子。
--
-- moves 存成紧凑字符串 `"7,7;8,8;6,7"`（列,行，都是 0..14）。最多 225 手、约 1.4KB，
-- 一次 UPDATE 就能落子，不用开一张子表再排序。
-- 颜色不单独存：第 0 手黑、第 1 手白，依次交替，看下标就知道。
--
-- status：waiting 等对方接受 / playing 对局中 / finished 已分胜负（留着算战绩）。
-- winner：black / white / draw，没结束时是 NULL。
CREATE TABLE IF NOT EXISTS gomoku_matches (
  id         TEXT PRIMARY KEY,
  black_id   TEXT NOT NULL,
  black_name TEXT NOT NULL DEFAULT '',
  white_id   TEXT,
  white_name TEXT NOT NULL DEFAULT '',
  status     TEXT NOT NULL CHECK (status IN ('waiting', 'playing', 'finished')),
  turn       TEXT NOT NULL CHECK (turn IN ('black', 'white')),
  moves      TEXT NOT NULL DEFAULT '',
  winner     TEXT CHECK (winner IN ('black', 'white', 'draw')),
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_gomoku_created ON gomoku_matches(created_at DESC);
