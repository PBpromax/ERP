package com.company.oms.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialRepository extends JpaRepository<Material, Long> {
    java.util.Optional<Material> findByScanTokenAndDeletedFalse(String scanToken);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Material m where m.scanToken = :token and m.deleted = false")
    java.util.Optional<Material> lockByScanToken(@org.springframework.data.repository.query.Param("token") String token);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Material m where m.id = :id and (m.deleted = false or m.deleted is null)")
    java.util.Optional<Material> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
