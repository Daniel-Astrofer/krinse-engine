-- Server-driven home surface composition overrides (partial JSON payloads).
-- Defaults live in code; rows here layer on top by priority.

CREATE TABLE IF NOT EXISTS public.home_ui_override (
    id              BIGSERIAL PRIMARY KEY,
    scope           VARCHAR(16)  NOT NULL,
    user_id         BIGINT,
    segment_key     VARCHAR(128),
    priority        INT          NOT NULL DEFAULT 0,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    starts_at       TIMESTAMP WITH TIME ZONE,
    ends_at         TIMESTAMP WITH TIME ZONE,
    payload         TEXT         NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_home_ui_override_scope
        CHECK (scope IN ('GLOBAL', 'USER', 'SEGMENT')),
    CONSTRAINT chk_home_ui_override_payload_nonempty
        CHECK (char_length(trim(payload)) > 1)
);

CREATE INDEX IF NOT EXISTS idx_home_ui_override_active_priority
    ON public.home_ui_override (active, priority DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_home_ui_override_user
    ON public.home_ui_override (user_id)
    WHERE user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_home_ui_override_segment
    ON public.home_ui_override (segment_key)
    WHERE segment_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_home_ui_override_window
    ON public.home_ui_override (starts_at, ends_at);
