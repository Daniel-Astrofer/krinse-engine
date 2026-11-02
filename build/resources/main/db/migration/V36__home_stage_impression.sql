-- Per-user consumption of home Communication Stage pieces (news / market banners).
-- Once a user has READ/SEEN a stage content edition, compose suppresses it until content changes.

CREATE TABLE IF NOT EXISTS public.home_stage_impression (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT       NOT NULL,
    stage_id             VARCHAR(128) NOT NULL,
    content_fingerprint  VARCHAR(64)  NOT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'READ',
    seen_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_home_stage_impression_status
        CHECK (status IN ('SEEN', 'READ', 'DISMISSED')),
    CONSTRAINT uq_home_stage_impression_user_fp
        UNIQUE (user_id, content_fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_home_stage_impression_user_stage
    ON public.home_stage_impression (user_id, stage_id);

CREATE INDEX IF NOT EXISTS idx_home_stage_impression_user_seen
    ON public.home_stage_impression (user_id, seen_at DESC);
