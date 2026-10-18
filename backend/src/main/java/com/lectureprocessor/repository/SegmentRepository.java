package com.lectureprocessor.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.domain.TranscriptSegment;

@Repository
public class SegmentRepository {

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replaceAll(String lectureId, List<TranscriptSegment> segments) {
        jdbc.update("DELETE FROM transcript_segment WHERE lecture_id = ?", lectureId);
        jdbc.batchUpdate("""
                        INSERT INTO transcript_segment (lecture_id, sequence, start_ms, end_ms, text)
                        VALUES (?,?,?,?,?)
                        """,
                segments.stream().map(s -> new Object[] {
                        lectureId, s.sequence(), s.startMs(), s.endMs(), s.text()
                }).toList());
    }

    public List<TranscriptSegment> findByLecture(String lectureId) {
        return jdbc.query("""
                        SELECT sequence, start_ms, end_ms, text FROM transcript_segment
                        WHERE lecture_id = ? ORDER BY sequence
                        """,
                (rs, rowNum) -> new TranscriptSegment(
                        rs.getInt("sequence"), rs.getLong("start_ms"),
                        rs.getLong("end_ms"), rs.getString("text")),
                lectureId);
    }

    /** Updates only the text of one segment; timestamps and order stay intact. */
    public int updateText(String lectureId, int sequence, String text) {
        return jdbc.update("""
                UPDATE transcript_segment SET text = ?
                WHERE lecture_id = ? AND sequence = ?
                """, text, lectureId, sequence);
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM transcript_segment WHERE lecture_id = ?", lectureId);
    }
}
