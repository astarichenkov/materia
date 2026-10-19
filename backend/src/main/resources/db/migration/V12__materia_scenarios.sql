-- Materia universal content processing: processing scenarios.
-- Additive only. Existing profiles and variants keep working with scenario = NULL,
-- which is rendered as a legacy profile instead of being assigned a new scenario.

ALTER TABLE lecture_processing_profile ADD COLUMN scenario TEXT;
ALTER TABLE lecture_output_variant ADD COLUMN scenario TEXT;
