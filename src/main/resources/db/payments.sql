-- Applied on NODUQ Supabase as migration payments_notices_and_devices.
-- Spring does not run this file. Keep it in sync with the remote schema.

create table public.devices (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete cascade,
  profile_id uuid references public.profiles (id) on delete cascade,
  employee_id uuid references public.employees (id) on delete cascade,
  push_token text not null unique,
  platform text not null default 'android',
  sms_reader boolean not null default false,
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  constraint devices_owner_xor_employee check (
    (profile_id is not null and employee_id is null)
    or (profile_id is null and employee_id is not null)
  )
);

create index devices_organization_id_idx on public.devices (organization_id);

alter table public.devices enable row level security;

-- Only payerName, amount and occurredAt are kept, per bancolombia-senders.json.
-- The raw message is never stored; fingerprint is a hash used to drop repeats.
create table public.payment_notices (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete cascade,
  source text not null check (source in ('sms', 'email')),
  payer_name text,
  amount numeric(14,2) check (amount is null or amount >= 0),
  currency text not null default 'COP',
  occurred_at timestamptz,
  received_at timestamptz not null default now(),
  fingerprint text not null,
  email_confirmed_at timestamptz,
  unparsed_excerpt text,
  created_at timestamptz not null default now(),
  unique (organization_id, fingerprint)
);

create index payment_notices_feed_idx on public.payment_notices (organization_id, received_at desc);

alter table public.payment_notices enable row level security;

create table public.gmail_connections (
  organization_id uuid primary key references public.organizations (id) on delete cascade,
  profile_id uuid not null references public.profiles (id) on delete cascade,
  gmail_address text not null,
  refresh_token text not null,
  history_id text,
  last_polled_at timestamptz,
  created_at timestamptz not null default now()
);

alter table public.gmail_connections enable row level security;
