-- Indexes for queries and cascades added since the tables were created.

-- Finding counts per session, and ON DELETE SET NULL when a session is deleted.
CREATE INDEX finding_session_idx ON finding (research_session_id) WHERE research_session_id IS NOT NULL;
-- ON DELETE SET NULL when a conversation or comparison is deleted.
CREATE INDEX finding_conversation_idx ON finding (conversation_id) WHERE conversation_id IS NOT NULL;
CREATE INDEX finding_comparison_idx ON finding (comparison_id) WHERE comparison_id IS NOT NULL;
-- Cascades when a filing is deleted.
CREATE INDEX finding_filing_idx ON finding (filing_id);
CREATE INDEX notification_filing_idx ON notification (filing_id);

-- The dashboard's latest completed comparisons.
CREATE INDEX filing_comparison_ready_idx ON filing_comparison (computed_at DESC, id DESC) WHERE status = 'READY';
