package ai.careerpilot.repo;

import ai.careerpilot.domain.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {}
