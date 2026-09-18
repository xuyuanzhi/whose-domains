-- Apply before enabling wesite.diagnostics.enabled. Timestamps are UTC epoch milliseconds.
CREATE TABLE IF NOT EXISTS WEB_SYSTEM_ISSUE (
 id varchar(36) CHARACTER SET ascii PRIMARY KEY,
 app varchar(16) NOT NULL, environment varchar(32) NOT NULL,
 fingerprint char(64) CHARACTER SET ascii NOT NULL,
 source varchar(24) NOT NULL, summary varchar(400) NOT NULL,
 route varchar(200) NOT NULL, exception_type varchar(160) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'open', occurrence_count bigint NOT NULL DEFAULT 0,
 first_seen_at bigint NOT NULL, last_seen_at bigint NOT NULL,
 last_release varchar(64) NOT NULL, resolved_release varchar(64) NOT NULL DEFAULT '',
 version bigint NOT NULL DEFAULT 0, managed_at bigint NOT NULL DEFAULT 0, resolved_at bigint NOT NULL DEFAULT 0,
 UNIQUE KEY UK_SYSTEM_ISSUE (app, environment, fingerprint),
 KEY IDX_SYSTEM_ISSUE_LIST (environment, status, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS WEB_SYSTEM_ISSUE_RECEIPT (
 app varchar(16) NOT NULL, environment varchar(32) NOT NULL,
 occurrence_key varchar(80) CHARACTER SET ascii NOT NULL,
 client_reported boolean NOT NULL DEFAULT false, created_at bigint NOT NULL,
 PRIMARY KEY (app, environment, occurrence_key), KEY IDX_RECEIPT_TIME (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS WEB_SYSTEM_ISSUE_EVENT (
 id varchar(36) CHARACTER SET ascii PRIMARY KEY,
 issue_id varchar(36) CHARACTER SET ascii NOT NULL,
 app varchar(16) NOT NULL, environment varchar(32) NOT NULL,
 occurrence_key varchar(80) CHARACTER SET ascii NOT NULL,
 route varchar(200) NOT NULL, method varchar(12) NOT NULL, http_status int NOT NULL,
 exception_type varchar(160) NOT NULL, safe_frames text NOT NULL,
 request_id varchar(36) CHARACTER SET ascii NOT NULL, release_name varchar(64) NOT NULL,
 occurred_at bigint NOT NULL,
 UNIQUE KEY UK_SYSTEM_EVENT (app, environment, occurrence_key),
 KEY IDX_ISSUE_EVENT (issue_id, occurred_at, id), KEY IDX_EVENT_TIME (occurred_at),
 CONSTRAINT FK_ISSUE_EVENT FOREIGN KEY (issue_id) REFERENCES WEB_SYSTEM_ISSUE(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS WEB_SYSTEM_ISSUE_NOTE (
 id varchar(36) CHARACTER SET ascii PRIMARY KEY, issue_id varchar(36) CHARACTER SET ascii NOT NULL,
 actor_id varchar(64) NOT NULL, old_status varchar(16) NOT NULL, new_status varchar(16) NOT NULL,
 note varchar(1000) NOT NULL, resolved_release varchar(64) NOT NULL, created_at bigint NOT NULL,
 KEY IDX_ISSUE_NOTE (issue_id, created_at, id),
 CONSTRAINT FK_ISSUE_NOTE FOREIGN KEY (issue_id) REFERENCES WEB_SYSTEM_ISSUE(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
