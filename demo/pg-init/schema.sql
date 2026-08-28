create table if not exists orders (
    id          text primary key,
    customer    text,
    amount      numeric,
    source      text default 'workbench',
    status      text default 'NEW',
    received_at timestamptz default now()
);
