// Repeating this UPDATE replaces the same counters; it never adds delivery counts.
export async function persistSample(DB, trace, status, elapsed) {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await DB.prepare(`UPDATE bench_samples SET elapsed=?,status=?,statements=?,rows_read=?,rows_written=?,
        fcm_calls=?,fcm_success=?,fcm_retry=?,within60=?,inline_count=?,sync_count=? WHERE id=?`)
        .bind(elapsed, status, trace.statements, trace.rowsRead, trace.rowsWritten,
          trace.fcmCalls, trace.fcmSuccess, trace.fcmRetry, trace.within60, trace.inline, trace.syncRequired, trace.id).run();
      return;
    } catch { if (attempt === 2) throw new Error('benchmark_record_failed'); }
  }
}
