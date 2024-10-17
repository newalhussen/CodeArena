CREATE TABLE app_user (
  id bigserial PRIMARY KEY,
  username text NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9_]{3,20}$'),
  password_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE session (
  token_hash text PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL
);

CREATE TABLE problem (
  id bigserial PRIMARY KEY,
  slug text NOT NULL UNIQUE,
  title text NOT NULL,
  difficulty text NOT NULL CHECK (difficulty IN ('easy', 'medium', 'hard')),
  function_name text NOT NULL,
  arg_names text NOT NULL,           -- JSON array of parameter names
  statement text NOT NULL
);

CREATE TABLE problem_topic (
  problem_id bigint NOT NULL REFERENCES problem(id) ON DELETE CASCADE,
  topic text NOT NULL,
  PRIMARY KEY (problem_id, topic)
);

CREATE TABLE test_case (
  id bigserial PRIMARY KEY,
  problem_id bigint NOT NULL REFERENCES problem(id) ON DELETE CASCADE,
  ord int NOT NULL,
  args text NOT NULL,                -- JSON array of arguments
  expected text NOT NULL,            -- JSON value
  hidden boolean NOT NULL DEFAULT true,
  UNIQUE (problem_id, ord)
);

CREATE TABLE submission (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  problem_id bigint NOT NULL REFERENCES problem(id) ON DELETE CASCADE,
  language text NOT NULL,
  code text NOT NULL,
  status text NOT NULL,
  passed int NOT NULL DEFAULT 0,
  total int NOT NULL DEFAULT 0,
  message text NOT NULL DEFAULT '',
  runtime_ms bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX submission_user_idx ON submission (user_id, created_at DESC);
CREATE INDEX submission_problem_idx ON submission (user_id, problem_id, status);
