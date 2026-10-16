-- Runs once, when the Postgres container is first created (mounted into /docker-entrypoint-initdb.d).
create table if not exists acks (
  id          bigserial primary key,
  message_id  text        not null,
  correlation text        not null,
  a_status    int         not null,
  acked_at    timestamptz not null default now()
);
create index if not exists acks_message_id on acks (message_id);
