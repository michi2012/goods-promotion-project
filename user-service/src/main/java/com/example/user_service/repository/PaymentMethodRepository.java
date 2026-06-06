package com.example.user_service.repository;

import com.example.user_service.entity.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {
    boolean existsByUserId(Long userId);
    List<PaymentMethod> findByUserId(Long userId);

    @Query("SELECT pm FROM PaymentMethod pm JOIN FETCH pm.user WHERE pm.user.userId = :userId AND pm.isDefault = true")
    Optional<PaymentMethod> findByUserUserIdAndIsDefaultTrue(String userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentMethod pm SET pm.isDefault = false WHERE pm.user.id = :userId")
    void resetDefaultPaymentMethod(@Param("userId") Long userId);
}
