-- ============================================================
-- XSR Companion cloud schema
-- Summary-only sync. No ride_points, no GPX.
-- ============================================================

create extension if not exists pgcrypto;

-- ------------------------------------------------------------
-- Profile (1:1 with auth user), auto-created on signup
-- ------------------------------------------------------------
create table if not exists public.profiles (
  id           uuid primary key references auth.users(id) on delete cascade,
  display_name text,
  created_at   timestamptz not null default now()
);

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles (id) values (new.id)
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ------------------------------------------------------------
-- Ride summary
-- ------------------------------------------------------------
create table if not exists public.rides (
  id              uuid primary key,
  user_id         uuid not null default auth.uid()
                    references auth.users(id) on delete cascade,
  start_time      bigint not null,
  end_time        bigint not null,
  duration_sec    int not null default 0,
  moving_sec      int not null default 0,
  distance_km     double precision not null default 0,
  top_speed_kmh   int not null default 0,
  avg_speed_kmh   double precision not null default 0,
  max_rpm         int not null default 0,
  avg_rpm         int not null default 0,
  max_coolant     int not null default 0,
  start_odo_km    double precision not null default 0,
  end_odo_km      double precision not null default 0,
  fuel_used_l     double precision not null default 0,
  avg_mileage_kml double precision not null default 0,
  notes           text,
  updated_at      bigint not null,
  deleted         boolean not null default false,
  created_at      timestamptz not null default now()
);

-- ------------------------------------------------------------
-- Fuel log
-- ------------------------------------------------------------
create table if not exists public.fuel_log (
  id           uuid primary key,
  user_id      uuid not null default auth.uid()
                 references auth.users(id) on delete cascade,
  timestamp    bigint not null,
  odometer_km  double precision not null,
  litres       double precision not null,
  price_per_l  double precision not null default 0,
  total_cost   double precision not null default 0,
  is_full      boolean not null default false,
  notes        text,
  updated_at   bigint not null,
  deleted      boolean not null default false,
  created_at   timestamptz not null default now()
);

-- ------------------------------------------------------------
-- Service log
-- ------------------------------------------------------------
create table if not exists public.service_log (
  id           uuid primary key,
  user_id      uuid not null default auth.uid()
                 references auth.users(id) on delete cascade,
  item_id      text not null,
  timestamp    bigint not null,
  odometer_km  double precision not null default 0,
  notes        text,
  cost         double precision not null default 0,
  updated_at   bigint not null,
  deleted      boolean not null default false,
  created_at   timestamptz not null default now()
);

-- ------------------------------------------------------------
-- DTC log
-- ------------------------------------------------------------
create table if not exists public.dtc_log (
  id           uuid primary key,
  user_id      uuid not null default auth.uid()
                 references auth.users(id) on delete cascade,
  timestamp    bigint not null,
  code         int not null,
  title        text,
  severity     text,
  odometer_km  double precision not null default 0,
  updated_at   bigint not null,
  deleted      boolean not null default false,
  created_at   timestamptz not null default now()
);

-- ------------------------------------------------------------
-- Bike state (was xsr_fuel_prefs + parts of maintenance prefs)
-- ------------------------------------------------------------
create table if not exists public.bike_state (
  user_id                    uuid primary key default auth.uid()
                               references auth.users(id) on delete cascade,
  current_odometer_km        double precision not null default 0,
  fuel_remaining_l           double precision,
  fuel_consumed_since_refuel_l double precision,
  fuel_lifetime_consumed_l   double precision,
  fuel_last_refuel_time      bigint,
  fuel_last_refuel_odo       double precision,
  maintenance_baselined      boolean not null default false,
  updated_at                 bigint not null
);

-- ------------------------------------------------------------
-- Maintenance state (per service item)
-- ------------------------------------------------------------
create table if not exists public.maintenance_state (
  user_id             uuid not null default auth.uid()
                        references auth.users(id) on delete cascade,
  item_id             text not null,
  last_service_odo_km double precision not null default 0,
  last_service_time   bigint not null default 0,
  interval_km         int,
  updated_at          bigint not null,
  primary key (user_id, item_id)
);

-- ------------------------------------------------------------
-- App settings (whitelisted keys, JSON bag)
-- ------------------------------------------------------------
create table if not exists public.user_settings (
  user_id    uuid primary key default auth.uid()
               references auth.users(id) on delete cascade,
  data       jsonb not null default '{}'::jsonb,
  updated_at bigint not null
);

-- ------------------------------------------------------------
-- Indexes
-- ------------------------------------------------------------
create index if not exists rides_user_start_idx
  on public.rides (user_id, start_time desc) where not deleted;
create index if not exists fuel_user_time_idx
  on public.fuel_log (user_id, timestamp desc) where not deleted;
create index if not exists service_user_time_idx
  on public.service_log (user_id, timestamp desc) where not deleted;
create index if not exists dtc_user_time_idx
  on public.dtc_log (user_id, timestamp desc) where not deleted;

-- ------------------------------------------------------------
-- Row Level Security (RLS)
-- ------------------------------------------------------------
alter table public.profiles          enable row level security;
alter table public.rides             enable row level security;
alter table public.fuel_log          enable row level security;
alter table public.service_log       enable row level security;
alter table public.dtc_log           enable row level security;
alter table public.bike_state        enable row level security;
alter table public.maintenance_state enable row level security;
alter table public.user_settings     enable row level security;

create policy profiles_own on public.profiles
  for all using (id = auth.uid()) with check (id = auth.uid());

create policy rides_own on public.rides
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy fuel_own on public.fuel_log
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy service_own on public.service_log
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy dtc_own on public.dtc_log
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy bike_state_own on public.bike_state
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy maintenance_own on public.maintenance_state
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy settings_own on public.user_settings
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Hardening
revoke all on all tables in schema public from anon;
grant select, insert, update, delete on all tables in schema public to authenticated;
