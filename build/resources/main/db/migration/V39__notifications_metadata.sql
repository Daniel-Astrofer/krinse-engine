-- Persist structured notification metadata so background poll / REST clients
-- receive amountBtc, rail, confirmations, etc. (previously only on STOMP WS).
ALTER TABLE public.notifications
    ADD COLUMN IF NOT EXISTS metadata_json TEXT;

COMMENT ON COLUMN public.notifications.metadata_json IS
    'Optional JSON object of string metadata (rail, creditedSats, amountBtc, …).';
