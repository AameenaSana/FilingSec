-- Sign-in through Supabase Auth (e.g. Google). The Supabase user id links an external
-- identity to exactly one FilingLens account; accounts without one use only a password.
ALTER TABLE app_user ADD COLUMN supabase_user_id UUID UNIQUE;
