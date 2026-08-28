create table if not exists orders (
    id          text primary key,
    customer    text,
    amount      numeric,
    source      text default 'workbench',
    received_at timestamptz default now()
);
