# Connection Pooling Configuration

## Overview

Atomikos TransactionsEssentials provides built-in connection pooling for both XA and non-XA datasources to maximize performance and resource utilization. However, there are scenarios where you may want to disable connection pooling and create fresh connections for each transaction.

## Disabling Connection Pooling

Starting with version 6.0.1, Atomikos supports a `disablePooling` configuration option that allows you to bypass the connection pool entirely.

### When to Disable Pooling

You might want to disable connection pooling in the following scenarios:

1. **Testing and Development**: When you want to ensure that each test or transaction uses a completely fresh connection
2. **Short-lived Applications**: For applications that run briefly and don't benefit from connection pooling overhead
3. **Resource Constraints**: When you want to minimize the number of persistent connections to the database
4. **Debugging**: When troubleshooting connection-related issues and want to eliminate pooling as a variable
5. **Cloud Environments**: In serverless or ephemeral environments where maintaining a connection pool is counterproductive

### Configuration

#### For Non-XA DataSources

```java
AtomikosNonXADataSourceBean dataSource = new AtomikosNonXADataSourceBean();
dataSource.setUniqueResourceName("myDataSource");
dataSource.setDriverClassName("com.mysql.jdbc.Driver");
dataSource.setUrl("jdbc:mysql://localhost:3306/mydb");
dataSource.setUser("username");
dataSource.setPassword("password");

// Disable connection pooling
dataSource.setDisablePooling(true);
```

#### For XA DataSources

```java
AtomikosDataSourceBean dataSource = new AtomikosDataSourceBean();
dataSource.setUniqueResourceName("myXADataSource");
dataSource.setXaDataSourceClassName("com.mysql.cj.jdbc.MysqlXADataSource");

Properties xaProps = new Properties();
xaProps.setProperty("URL", "jdbc:mysql://localhost:3306/mydb");
xaProps.setProperty("user", "username");
xaProps.setProperty("password", "password");
dataSource.setXaProperties(xaProps);

// Disable connection pooling
dataSource.setDisablePooling(true);
```

#### Spring Configuration

Using Spring XML configuration:

```xml
<bean id="dataSource" class="com.atomikos.jdbc.AtomikosDataSourceBean" 
      init-method="init" destroy-method="close">
    <property name="uniqueResourceName" value="myDataSource"/>
    <property name="xaDataSourceClassName" value="com.mysql.cj.jdbc.MysqlXADataSource"/>
    <property name="xaProperties">
        <props>
            <prop key="URL">jdbc:mysql://localhost:3306/mydb</prop>
            <prop key="user">username</prop>
            <prop key="password">password</prop>
        </props>
    </property>
    <property name="disablePooling" value="true"/>
</bean>
```

Using Spring Boot application.properties:

```properties
# XA DataSource configuration
atomikos.datasource.unique-resource-name=myDataSource
atomikos.datasource.xa-data-source-class-name=com.mysql.cj.jdbc.MysqlXADataSource
atomikos.datasource.xa-properties.URL=jdbc:mysql://localhost:3306/mydb
atomikos.datasource.xa-properties.user=username
atomikos.datasource.xa-properties.password=password

# Disable pooling
atomikos.datasource.disable-pooling=true
```

### Behavior When Pooling is Disabled

When `disablePooling` is set to `true`:

1. **No Pool Initialization**: The connection pool is not created during datasource initialization
2. **Fresh Connections**: Each call to `getConnection()` creates a new physical connection to the database
3. **Immediate Cleanup**: Connections are closed immediately when the application closes them or when the transaction completes
4. **Ignored Pool Settings**: Pool-related properties (`minPoolSize`, `maxPoolSize`, `borrowConnectionTimeout`, `maxIdleTime`, etc.) are ignored
5. **Connection Factory**: The underlying connection factory is still used to create connections, ensuring proper XA resource management

### Important Considerations

1. **Performance Impact**: Creating a new connection for each transaction has overhead. This mode is not recommended for high-throughput production systems
2. **Transaction Management**: JTA transaction management still works correctly - connections are properly enlisted in transactions
3. **Resource Management**: Each connection is a separate database session, which may consume more database resources
4. **Connection Limits**: Ensure your database can handle the connection rate if you have many concurrent transactions
5. **No Validation**: Connection test queries are not executed since connections are always fresh

### Code Example

Here's a complete example demonstrating the use of unpooled connections:

```java
import com.atomikos.jdbc.AtomikosDataSourceBean;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class UnpooledDataSourceExample {
    
    public static void main(String[] args) throws SQLException {
        // Create and configure datasource
        AtomikosDataSourceBean dataSource = new AtomikosDataSourceBean();
        dataSource.setUniqueResourceName("testDB");
        dataSource.setXaDataSourceClassName("org.h2.jdbcx.JdbcDataSource");
        
        Properties props = new Properties();
        props.setProperty("URL", "jdbc:h2:mem:test");
        dataSource.setXaProperties(props);
        
        // Disable pooling
        dataSource.setDisablePooling(true);
        
        try {
            dataSource.init();
            
            // Each getConnection() creates a fresh connection
            Connection conn1 = dataSource.getConnection();
            System.out.println("Got fresh connection: " + conn1);
            conn1.close();
            
            Connection conn2 = dataSource.getConnection();
            System.out.println("Got another fresh connection: " + conn2);
            conn2.close();
            
        } finally {
            dataSource.close();
        }
    }
}
```

### Migration from Pooled to Unpooled

If you're migrating an existing application from pooled to unpooled connections:

1. Set `disablePooling=true` in your configuration
2. Remove or ignore pool-related configuration (minPoolSize, maxPoolSize, etc.)
3. Monitor your application's connection usage and database performance
4. Consider increasing database connection limits if needed
5. Test thoroughly under expected load

### Switching Back to Pooling

To re-enable connection pooling, simply:

1. Set `disablePooling=false` (or remove the property, as pooling is enabled by default)
2. Configure appropriate pool settings (minPoolSize, maxPoolSize, etc.)
3. Restart your application

## Related Configuration Properties

Even when pooling is disabled, these properties remain relevant:

- `uniqueResourceName`: Still required to identify the resource for recovery
- `defaultIsolationLevel`: Connection isolation level still applies
- `localTransactionMode`: Transaction mode configuration still applies
- `testQuery`: Ignored when pooling is disabled (connections are always fresh)

## Default Behavior

By default, `disablePooling` is set to `false`, meaning connection pooling is enabled. This is the recommended configuration for most production scenarios.

## See Also

- [Atomikos Documentation](http://www.atomikos.com/Documentation/)
- [Connection Pool Configuration Guide](http://www.atomikos.com/Documentation/ConfiguringConnectionPools)
- [JTA Transaction Management](http://www.atomikos.com/Documentation/JTA)
