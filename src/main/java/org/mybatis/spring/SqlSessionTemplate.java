/*
 * Copyright 2010-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mybatis.spring;

import static java.lang.reflect.Proxy.newProxyInstance;

import static org.apache.ibatis.reflection.ExceptionUtil.unwrapThrowable;
import static org.mybatis.spring.SqlSessionUtils.closeSqlSession;
import static org.mybatis.spring.SqlSessionUtils.getSqlSession;
import static org.mybatis.spring.SqlSessionUtils.isSqlSessionTransactional;
import static org.springframework.util.Assert.notNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.support.PersistenceExceptionTranslator;

/**
 *
 * Spring解决SqlSession线程安全问题的思路就是动态代理与ThreadLocal的运用，我们可以触类旁通：当遇到线程不安全的类，但是又想当作线程安全的类使用，
 * 则可以使用ThreadLocal进行线程上下文的隔离，此处的动态代理技术更好的解决了上层API调用的非侵入性，保证API接口调用的高内聚、低耦合原则
 *
 * 通过源码注释可以看到SqlSessionTemplate是线程安全的类，并且实现了SqlSession接口，
 * 、也就是说我们可以通过SqlSessionTemplate来代替以往的DefaultSqlSession完成对数据库CRUD操作，并且还保证单例线程安全。
 *
 * DefaultSqlSession 和 SqlSessionTemplate之间的区别:
 *
 * 1.  SqlSessionTemplate是线程安全的，可以被多个DAO共享，而DefaultSqlSession线程不安全。至于线程不安全的原因显而易见，
 * 因为一个DefaultSqlSession实际可以代表一个Connection，如果在多线程中使用时，一个线程在执行数据库操作，
 * 另一个线程执行别的操作时直接将事务提交了，岂不是就乱套了。因此MyBatis官方文档建议DefaultSqlSession的最佳作用域是方法作用域。
 *
 * 2. SqlSessionTemplate是不支持事务以及关闭方法的，也就是commit、rollback以及close。如果显式调用这几个方法，会抛出一个异常。
 * 事务的提交回滚以及SqlSession的关闭全都由自己自动管理，不需要外部程序参与。
 *
 *
 * Thread safe, Spring managed, {@code SqlSession} that works with Spring transaction management to ensure that that the
 * actual SqlSession used is the one associated with the current Spring transaction. In addition, it manages the session
 * life-cycle, including closing, committing or rolling back the session as necessary based on the Spring transaction
 * configuration.
 * <p>
 * The template needs a SqlSessionFactory to create SqlSessions, passed as a constructor argument. It also can be
 * constructed indicating the executor type to be used, if not, the default executor type, defined in the session
 * factory will be used.
 * <p>
 * This template converts MyBatis PersistenceExceptions into unchecked DataAccessExceptions, using, by default, a
 * {@code MyBatisExceptionTranslator}.
 * <p>
 * Because SqlSessionTemplate is thread safe, a single instance can be shared by all DAOs; there should also be a small
 * memory savings by doing this. This pattern can be used in Spring configuration files as follows:
 *
 * <pre class="code">
 * {@code
 * <bean id="sqlSessionTemplate" class="org.mybatis.spring.SqlSessionTemplate">
 *   <constructor-arg ref="sqlSessionFactory" />
 * </bean>
 * }
 * </pre>
 *
 * @author Putthiphong Boonphong
 * @author Hunter Presnall
 * @author Eduardo Macarron
 *
 * @see SqlSessionFactory
 * @see MyBatisExceptionTranslator
 */
public class SqlSessionTemplate implements SqlSession, DisposableBean {

  private final SqlSessionFactory sqlSessionFactory;

  private final ExecutorType executorType;

  private final SqlSession sqlSessionProxy;

  private final PersistenceExceptionTranslator exceptionTranslator;

  /**
   * Constructs a Spring managed SqlSession with the {@code SqlSessionFactory} provided as an argument.
   *
   * @param sqlSessionFactory
   *          a factory of SqlSession
   */
  public SqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
    this(sqlSessionFactory, sqlSessionFactory.getConfiguration().getDefaultExecutorType());
  }

  /**
   * Constructs a Spring managed SqlSession with the {@code SqlSessionFactory} provided as an argument and the given
   * {@code ExecutorType} {@code ExecutorType} cannot be changed once the {@code SqlSessionTemplate} is constructed.
   *
   * @param sqlSessionFactory
   *          a factory of SqlSession
   * @param executorType
   *          an executor type on session
   */
  public SqlSessionTemplate(SqlSessionFactory sqlSessionFactory, ExecutorType executorType) {
    this(sqlSessionFactory, executorType,
        new MyBatisExceptionTranslator(sqlSessionFactory.getConfiguration().getEnvironment().getDataSource(), true));
  }

  /**
   * sqlSessionFactory: 用于创建SqlSesion的工厂类，可以使用SqlSessionFactoryBean来构建.
   *
   * executorType: 指定SqlSession中Executor的类型，默认是SimpleExecutor，有以下几个选项
   * ExecutorType.SIMPLE、ExecutorType.REUSE、 ExecutorType.BATCH
   *
   * exceptionTranslator: 异常转换器，将MyBatis中的异常转换成Spring中的DataAccessException异常
   *
   *
   * Constructs a Spring managed {@code SqlSession} with the given {@code SqlSessionFactory} and {@code ExecutorType}. A
   * custom {@code SQLExceptionTranslator} can be provided as an argument so any {@code PersistenceException} thrown by
   * MyBatis can be custom translated to a {@code RuntimeException} The {@code SQLExceptionTranslator} can also be null
   * and thus no exception translation will be done and MyBatis exceptions will be thrown
   *
   * @param sqlSessionFactory
   *          a factory of SqlSession
   * @param executorType
   *          an executor type on session
   * @param exceptionTranslator
   *          a translator of exception
   */
  public SqlSessionTemplate(SqlSessionFactory sqlSessionFactory, ExecutorType executorType,
      PersistenceExceptionTranslator exceptionTranslator) {

    //对必要参数的非空判断
    notNull(sqlSessionFactory, "Property 'sqlSessionFactory' is required");
    notNull(executorType, "Property 'executorType' is required");

    this.sqlSessionFactory = sqlSessionFactory;
    this.executorType = executorType;
    this.exceptionTranslator = exceptionTranslator;
    // 使用JDK动态代理创建一个SqlSession代理，以后所有的数据库操作就委托给它啦
    // 因此这一步是关键
    this.sqlSessionProxy = (SqlSession) newProxyInstance(SqlSessionFactory.class.getClassLoader(),
        new Class[] { SqlSession.class }, new SqlSessionInterceptor());
  }

  public SqlSessionFactory getSqlSessionFactory() {
    return this.sqlSessionFactory;
  }

  public ExecutorType getExecutorType() {
    return this.executorType;
  }

  public PersistenceExceptionTranslator getPersistenceExceptionTranslator() {
    return this.exceptionTranslator;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T selectOne(String statement) {
    return this.sqlSessionProxy.selectOne(statement);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T selectOne(String statement, Object parameter) {
    return this.sqlSessionProxy.selectOne(statement, parameter);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
    return this.sqlSessionProxy.selectMap(statement, mapKey);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
    return this.sqlSessionProxy.selectMap(statement, parameter, mapKey);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey, RowBounds rowBounds) {
    return this.sqlSessionProxy.selectMap(statement, parameter, mapKey, rowBounds);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Cursor<T> selectCursor(String statement) {
    return this.sqlSessionProxy.selectCursor(statement);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Cursor<T> selectCursor(String statement, Object parameter) {
    return this.sqlSessionProxy.selectCursor(statement, parameter);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Cursor<T> selectCursor(String statement, Object parameter, RowBounds rowBounds) {
    return this.sqlSessionProxy.selectCursor(statement, parameter, rowBounds);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <E> List<E> selectList(String statement) {
    return this.sqlSessionProxy.selectList(statement);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <E> List<E> selectList(String statement, Object parameter) {
    return this.sqlSessionProxy.selectList(statement, parameter);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
    return this.sqlSessionProxy.selectList(statement, parameter, rowBounds);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void select(String statement, ResultHandler handler) {
    this.sqlSessionProxy.select(statement, handler);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void select(String statement, Object parameter, ResultHandler handler) {
    this.sqlSessionProxy.select(statement, parameter, handler);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void select(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {
    this.sqlSessionProxy.select(statement, parameter, rowBounds, handler);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int insert(String statement) {
    return this.sqlSessionProxy.insert(statement);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int insert(String statement, Object parameter) {
    return this.sqlSessionProxy.insert(statement, parameter);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int update(String statement) {
    return this.sqlSessionProxy.update(statement);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int update(String statement, Object parameter) {
    return this.sqlSessionProxy.update(statement, parameter);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int delete(String statement) {
    return this.sqlSessionProxy.delete(statement);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int delete(String statement, Object parameter) {
    return this.sqlSessionProxy.delete(statement, parameter);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T getMapper(Class<T> type) {
    return getConfiguration().getMapper(type, this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void commit() {
    throw new UnsupportedOperationException("Manual commit is not allowed over a Spring managed SqlSession");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void commit(boolean force) {
    throw new UnsupportedOperationException("Manual commit is not allowed over a Spring managed SqlSession");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rollback() {
    throw new UnsupportedOperationException("Manual rollback is not allowed over a Spring managed SqlSession");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rollback(boolean force) {
    throw new UnsupportedOperationException("Manual rollback is not allowed over a Spring managed SqlSession");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() {
    throw new UnsupportedOperationException("Manual close is not allowed over a Spring managed SqlSession");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clearCache() {
    this.sqlSessionProxy.clearCache();
  }

  /**
   * {@inheritDoc}
   *
   */
  @Override
  public Configuration getConfiguration() {
    return this.sqlSessionFactory.getConfiguration();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Connection getConnection() {
    return this.sqlSessionProxy.getConnection();
  }

  /**
   * {@inheritDoc}
   *
   * @since 1.0.2
   *
   */
  @Override
  public List<BatchResult> flushStatements() {
    return this.sqlSessionProxy.flushStatements();
  }

  /**
   * Allow gently dispose bean:
   *
   * <pre>
   * {@code
   *
   * <bean id="sqlSession" class="org.mybatis.spring.SqlSessionTemplate">
   *  <constructor-arg index="0" ref="sqlSessionFactory" />
   * </bean>
   * }
   * </pre>
   *
   * The implementation of {@link DisposableBean} forces spring context to use {@link DisposableBean#destroy()} method
   * instead of {@link SqlSessionTemplate#close()} to shutdown gently.
   *
   * @see SqlSessionTemplate#close()
   * @see "org.springframework.beans.factory.support.DisposableBeanAdapter#inferDestroyMethodIfNecessary(Object, RootBeanDefinition)"
   * @see "org.springframework.beans.factory.support.DisposableBeanAdapter#CLOSE_METHOD_NAME"
   */
  @Override
  public void destroy() throws Exception {
    // This method forces spring disposer to avoid call of SqlSessionTemplate.close() which gives
    // UnsupportedOperationException
  }

  /**
   * 代理拦截
   *
   * Proxy needed to route MyBatis method calls to the proper SqlSession got from Spring's Transaction Manager It also
   * unwraps exceptions thrown by {@code Method#invoke(Object, Object...)} to pass a {@code PersistenceException} to the
   * {@code PersistenceExceptionTranslator}.
   */
  private class SqlSessionInterceptor implements InvocationHandler {
    // 拦截方法，每当调用sqlSession中的方法时，都会先进入到这里。

    /**
     * 1. 通过getSqlSession方法获取SqlSession对象（如果使用了事务，从Spring事务上下文获取）
     * 2. 调用SqlSession的接口方法操作数据库获取结果
     * 3. 返回结果集
     * 4. 若发生异常则转换后抛出异常，并最终关闭SqlSession对象
     *
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

      //获取SqlSession（这个sqlSession才是真正使用的，它不是线程安全的）
      //这个方法可以根据Spring的事务上下文来获取事务范围内的SqlSession
      SqlSession sqlSession = getSqlSession(SqlSessionTemplate.this.sqlSessionFactory,
          SqlSessionTemplate.this.executorType, SqlSessionTemplate.this.exceptionTranslator);
      try {
        //调用sqlSession对象的方法（select、update等）
        Object result = method.invoke(sqlSession, args);
        //判断是否为事务操作，如果未被Spring事务托管则自动提交commit
        if (!isSqlSessionTransactional(sqlSession, SqlSessionTemplate.this.sqlSessionFactory)) {
          // force commit even on non-dirty sessions because some databases require
          // a commit/rollback before calling close()
          sqlSession.commit(true);
        }
        return result;
      } catch (Throwable t) {
        //如果出现异常则根据情况转换后抛出
        Throwable unwrapped = unwrapThrowable(t);
        if (SqlSessionTemplate.this.exceptionTranslator != null && unwrapped instanceof PersistenceException) {
          // release the connection to avoid a deadlock if the translator is no loaded. See issue #22
          closeSqlSession(sqlSession, SqlSessionTemplate.this.sqlSessionFactory);
          sqlSession = null;
          Throwable translated = SqlSessionTemplate.this.exceptionTranslator
              .translateExceptionIfPossible((PersistenceException) unwrapped);
          if (translated != null) {
            unwrapped = translated;
          }
        }
        throw unwrapped;
      } finally {
        //最终关闭sqlSession对象
        if (sqlSession != null) {
          closeSqlSession(sqlSession, SqlSessionTemplate.this.sqlSessionFactory);
        }
      }
    }
  }

}
