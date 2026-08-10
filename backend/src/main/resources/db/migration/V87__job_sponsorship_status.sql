-- Global Job Discovery Expansion — a richer, nullable sponsorship signal alongside the existing
-- jobs.sponsorship_available boolean (untouched, not removed, not repurposed). The boolean stays
-- the coarse yes/no/unknown signal every existing caller already reads; sponsorship_status adds
-- the CONFIRMED/MENTIONED/UNKNOWN/NOT_SUPPORTED distinction the visa-signal classifier produces.
-- Nullable, additive, no default — existing rows simply have no value until re-enriched.
ALTER TABLE jobs ADD COLUMN sponsorship_status VARCHAR(30);
