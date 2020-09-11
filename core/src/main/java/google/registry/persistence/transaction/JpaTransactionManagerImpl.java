// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.persistence.transaction;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.util.AbstractMap.SimpleEntry;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig;
import google.registry.persistence.JpaRetries;
import google.registry.persistence.VKey;
import google.registry.util.Clock;
import google.registry.util.Retrier;
import google.registry.util.SystemSleeper;
import java.lang.reflect.Field;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;
import org.joda.time.DateTime;

/** Implementation of {@link JpaTransactionManager} for JPA compatible database. */
public class JpaTransactionManagerImpl implements JpaTransactionManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Retrier retrier = new Retrier(new SystemSleeper(), 3);

  // EntityManagerFactory is thread safe.
  private final EntityManagerFactory emf;
  private final Clock clock;
  // TODO(shicong): Investigate alternatives for managing transaction information. ThreadLocal adds
  //  an unnecessary restriction that each request has to be processed by one thread synchronously.
  private final ThreadLocal<TransactionInfo> transactionInfo =
      ThreadLocal.withInitial(TransactionInfo::new);

  public JpaTransactionManagerImpl(EntityManagerFactory emf, Clock clock) {
    this.emf = emf;
    this.clock = clock;
  }

  @Override
  public void teardown() {
    emf.close();
  }

  @Override
  public EntityManager getEntityManager() {
    if (transactionInfo.get().entityManager == null) {
      throw new PersistenceException(
          "No EntityManager has been initialized. getEntityManager() must be invoked in the scope"
              + " of a transaction");
    }
    return transactionInfo.get().entityManager;
  }

  @Override
  public boolean inTransaction() {
    return transactionInfo.get().inTransaction;
  }

  @Override
  public void assertInTransaction() {
    if (!inTransaction()) {
      throw new IllegalStateException("Not in a transaction");
    }
  }

  @Override
  public <T> T transact(Supplier<T> work) {
    // TODO(shicong): Investigate removing transactNew functionality after migration as it may
    //  be same as this one.
    return retrier.callWithRetry(
        () -> {
          if (inTransaction()) {
            return work.get();
          }
          TransactionInfo txnInfo = transactionInfo.get();
          txnInfo.entityManager = emf.createEntityManager();
          EntityTransaction txn = txnInfo.entityManager.getTransaction();
          try {
            txn.begin();
            txnInfo.start(clock);
            T result = work.get();
            txnInfo.recordTransaction();
            txn.commit();
            return result;
          } catch (RuntimeException | Error e) {
            // Error is unchecked!
            try {
              txn.rollback();
              logger.atWarning().log("Error during transaction; transaction rolled back");
            } catch (Throwable rollbackException) {
              logger.atSevere().withCause(rollbackException).log(
                  "Rollback failed; suppressing error");
            }
            throw e;
          } finally {
            txnInfo.clear();
          }
        },
        JpaRetries::isFailedTxnRetriable);
  }

  @Override
  public <T> T transactNoRetry(Supplier<T> work) {
    if (inTransaction()) {
      return work.get();
    }
    TransactionInfo txnInfo = transactionInfo.get();
    txnInfo.entityManager = emf.createEntityManager();
    EntityTransaction txn = txnInfo.entityManager.getTransaction();
    try {
      txn.begin();
      txnInfo.start(clock);
      T result = work.get();
      txnInfo.recordTransaction();
      txn.commit();
      return result;
    } catch (RuntimeException | Error e) {
      // Error is unchecked!
      try {
        txn.rollback();
        logger.atWarning().log("Error during transaction; transaction rolled back");
      } catch (Throwable rollbackException) {
        logger.atSevere().withCause(rollbackException).log("Rollback failed; suppressing error");
      }
      throw e;
    } finally {
      txnInfo.clear();
    }
  }

  @Override
  public void transact(Runnable work) {
    transact(
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public void transactNoRetry(Runnable work) {
    transactNoRetry(
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public <T> T transactNew(Supplier<T> work) {
    return transact(work);
  }

  @Override
  public void transactNew(Runnable work) {
    transact(work);
  }

  @Override
  public <T> T transactNewReadOnly(Supplier<T> work) {
    return retrier.callWithRetry(
        () ->
            transact(
                () -> {
                  getEntityManager().createNativeQuery("SET TRANSACTION READ ONLY").executeUpdate();
                  return work.get();
                }),
        JpaRetries::isFailedQueryRetriable);
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    transactNewReadOnly(
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public <T> T doTransactionless(Supplier<T> work) {
    return retrier.callWithRetry(() -> transact(work), JpaRetries::isFailedQueryRetriable);
  }

  @Override
  public DateTime getTransactionTime() {
    assertInTransaction();
    TransactionInfo txnInfo = transactionInfo.get();
    if (txnInfo.transactionTime == null) {
      throw new PersistenceException("In a transaction but transactionTime is null");
    }
    return txnInfo.transactionTime;
  }

  @Override
  public void saveNew(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    assertInTransaction();
    getEntityManager().persist(entity);
    transactionInfo.get().addUpdate(entity);
  }

  @Override
  public void saveAllNew(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    assertInTransaction();
    entities.forEach(this::saveNew);
  }

  @Override
  public void saveNewOrUpdate(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    assertInTransaction();
    getEntityManager().merge(entity);
    transactionInfo.get().addUpdate(entity);
  }

  @Override
  public void saveNewOrUpdateAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    assertInTransaction();
    entities.forEach(this::saveNewOrUpdate);
  }

  @Override
  public void update(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    assertInTransaction();
    checkArgument(checkExists(entity), "Given entity does not exist");
    getEntityManager().merge(entity);
    transactionInfo.get().addUpdate(entity);
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    assertInTransaction();
    entities.forEach(this::update);
  }

  @Override
  public <T> boolean checkExists(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    EntityType<?> entityType = getEntityType(key.getKind());
    ImmutableSet<EntityId> entityIds = getEntityIdsFromSqlKey(entityType, key.getSqlKey());
    return checkExists(entityType.getName(), entityIds);
  }

  @Override
  public boolean checkExists(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    EntityType<?> entityType = getEntityType(entity.getClass());
    ImmutableSet<EntityId> entityIds = getEntityIdsFromEntity(entityType, entity);
    return checkExists(entityType.getName(), entityIds);
  }

  private boolean checkExists(String entityName, ImmutableSet<EntityId> entityIds) {
    assertInTransaction();
    TypedQuery<Integer> query =
        getEntityManager()
            .createQuery(
                String.format("SELECT 1 FROM %s WHERE %s", entityName, getAndClause(entityIds)),
                Integer.class)
            .setMaxResults(1);
    entityIds.forEach(entityId -> query.setParameter(entityId.name, entityId.value));
    return query.getResultList().size() > 0;
  }

  @Override
  public <T> Optional<T> maybeLoad(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    assertInTransaction();
    return Optional.ofNullable(getEntityManager().find(key.getKind(), key.getSqlKey()));
  }

  @Override
  public <T> T load(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    assertInTransaction();
    T result = getEntityManager().find(key.getKind(), key.getSqlKey());
    if (result == null) {
      throw new NoSuchElementException(key.toString());
    }
    return result;
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> load(Iterable<? extends VKey<? extends T>> keys) {
    checkArgumentNotNull(keys, "keys must be specified");
    assertInTransaction();
    return StreamSupport.stream(keys.spliterator(), false)
        // Accept duplicate keys.
        .distinct()
        .map(
            key ->
                new SimpleEntry<VKey<? extends T>, T>(
                    key, getEntityManager().find(key.getKind(), key.getSqlKey())))
        .filter(entry -> entry.getValue() != null)
        .collect(toImmutableMap(Entry::getKey, Entry::getValue));
  }

  @Override
  public <T> ImmutableList<T> loadAll(Class<T> clazz) {
    checkArgumentNotNull(clazz, "clazz must be specified");
    assertInTransaction();
    return ImmutableList.copyOf(
        getEntityManager()
            .createQuery(
                String.format("SELECT entity FROM %s entity", getEntityType(clazz).getName()),
                clazz)
            .getResultList());
  }

  private int internalDelete(VKey<?> key) {
    checkArgumentNotNull(key, "key must be specified");
    assertInTransaction();
    EntityType<?> entityType = getEntityType(key.getKind());
    ImmutableSet<EntityId> entityIds = getEntityIdsFromSqlKey(entityType, key.getSqlKey());
    String sql =
        String.format("DELETE FROM %s WHERE %s", entityType.getName(), getAndClause(entityIds));
    Query query = getEntityManager().createQuery(sql);
    entityIds.forEach(entityId -> query.setParameter(entityId.name, entityId.value));
    transactionInfo.get().addDelete(key);
    return query.executeUpdate();
  }

  @Override
  public void delete(VKey<?> key) {
    internalDelete(key);
  }

  @Override
  public void delete(Iterable<? extends VKey<?>> vKeys) {
    checkArgumentNotNull(vKeys, "vKeys must be specified");
    vKeys.forEach(this::internalDelete);
  }

  @Override
  public <T> void assertDelete(VKey<T> key) {
    if (internalDelete(key) != 1) {
      throw new IllegalArgumentException(
          String.format("Error deleting the entity of the key: %s", key.getSqlKey()));
    }
  }

  private <T> EntityType<T> getEntityType(Class<T> clazz) {
    return emf.getMetamodel().entity(clazz);
  }

  private static class EntityId {
    private String name;
    private Object value;

    private EntityId(String name, Object value) {
      this.name = name;
      this.value = value;
    }
  }

  private static ImmutableSet<EntityId> getEntityIdsFromEntity(
      EntityType<?> entityType, Object entity) {
    if (entityType.hasSingleIdAttribute()) {
      String idName = entityType.getId(entityType.getIdType().getJavaType()).getName();
      Object idValue = getFieldValue(entity, idName);
      return ImmutableSet.of(new EntityId(idName, idValue));
    } else {
      return getEntityIdsFromIdContainer(entityType, entity);
    }
  }

  private static ImmutableSet<EntityId> getEntityIdsFromSqlKey(
      EntityType<?> entityType, Object sqlKey) {
    if (entityType.hasSingleIdAttribute()) {
      String idName = entityType.getId(entityType.getIdType().getJavaType()).getName();
      return ImmutableSet.of(new EntityId(idName, sqlKey));
    } else {
      return getEntityIdsFromIdContainer(entityType, sqlKey);
    }
  }

  private static ImmutableSet<EntityId> getEntityIdsFromIdContainer(
      EntityType<?> entityType, Object idContainer) {
    return entityType.getIdClassAttributes().stream()
        .map(SingularAttribute::getName)
        .map(
            idName -> {
              Object idValue = getFieldValue(idContainer, idName);
              return new EntityId(idName, idValue);
            })
        .collect(toImmutableSet());
  }

  private String getAndClause(ImmutableSet<EntityId> entityIds) {
    return entityIds.stream()
        .map(entityId -> String.format("%s = :%s", entityId.name, entityId.name))
        .collect(joining(" AND "));
  }

  private static Object getFieldValue(Object object, String fieldName) {
    try {
      Field field = getField(object.getClass(), fieldName);
      field.setAccessible(true);
      return field.get(object);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /** Gets the field definition from clazz or any superclass. */
  private static Field getField(Class clazz, String fieldName) throws NoSuchFieldException {
    try {
      // Note that we have to use getDeclaredField() for this, getField() just finds public fields.
      return clazz.getDeclaredField(fieldName);
    } catch (NoSuchFieldException e) {
      Class base = clazz.getSuperclass();
      if (base != null) {
        return getField(base, fieldName);
      } else {
        throw e;
      }
    }
  }

  private static class TransactionInfo {
    EntityManager entityManager;
    boolean inTransaction = false;
    DateTime transactionTime;

    // Serializable representation of the transaction to be persisted in the Transaction table.
    Transaction.Builder contentsBuilder;

    /** Start a new transaction. */
    private void start(Clock clock) {
      checkArgumentNotNull(clock);
      inTransaction = true;
      transactionTime = clock.nowUtc();
      if (RegistryConfig.getCloudSqlReplicateTransactions()) {
        contentsBuilder = new Transaction.Builder();
      }
    }

    private void clear() {
      inTransaction = false;
      transactionTime = null;
      contentsBuilder = null;
      if (entityManager != null) {
        // Close this EntityManager just let the connection pool be able to reuse it, it doesn't
        // close the underlying database connection.
        entityManager.close();
        entityManager = null;
      }
    }

    private void addUpdate(Object entity) {
      if (contentsBuilder != null) {
        contentsBuilder.addUpdate(entity);
      }
    }

    private void addDelete(VKey<?> key) {
      if (contentsBuilder != null) {
        contentsBuilder.addDelete(key);
      }
    }

    private void recordTransaction() {
      if (contentsBuilder != null) {
        Transaction persistedTxn = contentsBuilder.build();
        if (!persistedTxn.isEmpty()) {
          entityManager.persist(persistedTxn.toEntity());
        }
      }
    }
  }
}
