// ============================================
// 1. 实体类 - 用于业务数据
// ============================================

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "data_item")
public class DataItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    private String data;
    private LocalDateTime createdAt;
    
    public DataItem() {
        this.createdAt = LocalDateTime.now();
    }
    
    public DataItem(String name, String data) {
        this();
        this.name = name;
        this.data = data;
    }
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
}

@Repository
public interface DataItemRepository extends JpaRepository<DataItem, Long> {
}

// ============================================
// 2. 执行状态类 - 用于序列化保存到 StepContext
// ============================================

import java.io.Serializable;

public class ExecutionState implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String lastCompletedTask; // null, "A", "B"
    private String failedTask;        // null, "A", "B"
    private String errorMessage;
    private long timestamp;
    
    public ExecutionState() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getLastCompletedTask() { return lastCompletedTask; }
    public void setLastCompletedTask(String lastCompletedTask) { 
        this.lastCompletedTask = lastCompletedTask;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getFailedTask() { return failedTask; }
    public void setFailedTask(String failedTask) { 
        this.failedTask = failedTask;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public long getTimestamp() { return timestamp; }
    
    @Override
    public String toString() {
        return "ExecutionState{" +
                "lastCompletedTask='" + lastCompletedTask + '\'' +
                ", failedTask='" + failedTask + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}

// ============================================
// 3. 核心 Batchlet - 包含 A 和 B 两个任务
// ============================================

import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.runtime.context.JobContext;
import jakarta.batch.runtime.context.StepContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Named("dataProcessBatchlet")
public class DataProcessBatchlet extends AbstractBatchlet {
    
    @Inject
    private DataItemRepository dataRepository;
    
    @Inject
    private PlatformTransactionManager transactionManager;
    
    @Inject
    private StepContext stepContext;
    
    @Inject
    private JobContext jobContext;
    
    @Inject
    @BatchProperty
    private String simulateFailTask; // "A" or "B"
    
    private volatile boolean stopped = false;
    
    @Override
    public String process() throws Exception {
        Long jobExecutionId = jobContext.getExecutionId();
        String stepName = stepContext.getStepName();
        
        System.out.println("============================================================");
        System.out.println("Starting Batchlet - JobExecutionId: " + jobExecutionId);
        System.out.println("Step: " + stepName);
        System.out.println("============================================================");
        
        // 1. 从 StepContext 获取持久化的执行状态
        ExecutionState state = getExecutionState();
        
        String lastCompleted = state.getLastCompletedTask();
        String failedTask = state.getFailedTask();
        
        System.out.println("Loaded execution state from StepContext:");
        System.out.println("  Last completed task: " + (lastCompleted != null ? lastCompleted : "NONE"));
        System.out.println("  Failed task: " + (failedTask != null ? failedTask : "NONE"));
        if (state.getErrorMessage() != null) {
            System.out.println("  Last error: " + state.getErrorMessage());
        }
        
        // 2. 判断从哪里开始执行
        boolean shouldExecuteA = (lastCompleted == null);
        boolean shouldExecuteB = ("A".equals(lastCompleted) || "B".equals(failedTask));
        
        try {
            // 3. 执行任务 A（如果需要）
            if (shouldExecuteA) {
                System.out.println("\n--- 要开始执行 A ---");
                executeTaskA(state);
                System.out.println("--- 执行 A 完成 ---\n");
                
                if (stopped) {
                    return "STOPPED";
                }
            } else {
                System.out.println("\nTask A already completed, skipping...\n");
            }
            
            // 4. 执行任务 B（如果需要）
            if (shouldExecuteB || shouldExecuteA) {
                System.out.println("--- 要开始执行 B ---");
                executeTaskB(state);
                System.out.println("--- 执行 B 完成 ---\n");
            } else {
                System.out.println("Task B already completed, skipping...\n");
            }
            
            System.out.println("============================================================");
            System.out.println("Batchlet completed successfully");
            System.out.println("Final state: " + state);
            System.out.println("============================================================");
            
            return "COMPLETED";
            
        } catch (Exception e) {
            System.err.println("============================================================");
            System.err.println("Batchlet failed: " + e.getMessage());
            System.err.println("Current state saved: " + state);
            System.err.println("============================================================");
            throw e;
        }
    }
    
    /**
     * 从 StepContext 获取执行状态
     * 关键方法：stepContext.getPersistentUserData()
     */
    private ExecutionState getExecutionState() {
        Object persistedData = stepContext.getPersistentUserData();
        
        if (persistedData instanceof ExecutionState) {
            System.out.println("Found persisted execution state from previous run");
            return (ExecutionState) persistedData;
        } else {
            System.out.println("No previous execution state found, creating new one");
            return new ExecutionState();
        }
    }
    
    /**
     * 保存执行状态到 StepContext
     * 关键方法：stepContext.setPersistentUserData()
     */
    private void saveExecutionState(ExecutionState state) {
        stepContext.setPersistentUserData(state);
        System.out.println("  State persisted: " + state);
    }
    
    /**
     * 任务A - 在独立事务中执行
     */
    private void executeTaskA(ExecutionState state) throws Exception {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus txStatus = transactionManager.getTransaction(def);
        
        try {
            System.out.println("  [Task A] Processing...");
            
            // 模拟失败
            if ("A".equals(simulateFailTask)) {
                throw new RuntimeException("Simulated failure in Task A");
            }
            
            // 实际业务逻辑
            DataItem item = new DataItem("TaskA-Result", "Data from Task A at " + System.currentTimeMillis());
            dataRepository.save(item);
            System.out.println("  [Task A] Saved data item: " + item.getName());
            
            Thread.sleep(500); // 模拟处理时间
            
            System.out.println("  [Task A] Business logic completed");
            
            // 提交事务
            transactionManager.commit(txStatus);
            System.out.println("  [Task A] Transaction committed ✓");
            
            // ★★★ 关键：任务A成功后，立即保存状态 ★★★
            state.setLastCompletedTask("A");
            state.setFailedTask(null);
            state.setErrorMessage(null);
            saveExecutionState(state);
            
        } catch (Exception e) {
            transactionManager.rollback(txStatus);
            System.err.println("  [Task A] Transaction rolled back ✗");
            
            // ★★★ 关键：任务A失败后，保存失败状态 ★★★
            state.setFailedTask("A");
            state.setErrorMessage(e.getMessage());
            saveExecutionState(state);
            
            throw new RuntimeException("Task A failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 任务B - 在独立事务中执行
     */
    private void executeTaskB(ExecutionState state) throws Exception {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus txStatus = transactionManager.getTransaction(def);
        
        try {
            System.out.println("  [Task B] Processing...");
            
            // 模拟失败
            if ("B".equals(simulateFailTask)) {
                throw new RuntimeException("Simulated failure in Task B");
            }
            
            // 实际业务逻辑
            DataItem item = new DataItem("TaskB-Result", "Data from Task B at " + System.currentTimeMillis());
            dataRepository.save(item);
            System.out.println("  [Task B] Saved data item: " + item.getName());
            
            Thread.sleep(500); // 模拟处理时间
            
            System.out.println("  [Task B] Business logic completed");
            
            // 提交事务
            transactionManager.commit(txStatus);
            System.out.println("  [Task B] Transaction committed ✓");
            
            // ★★★ 关键：任务B成功后，立即保存状态 ★★★
            state.setLastCompletedTask("B");
            state.setFailedTask(null);
            state.setErrorMessage(null);
            saveExecutionState(state);
            
        } catch (Exception e) {
            transactionManager.rollback(txStatus);
            System.err.println("  [Task B] Transaction rolled back ✗");
            
            // ★★★ 关键：任务B失败后，保存失败状态 ★★★
            state.setFailedTask("B");
            state.setErrorMessage(e.getMessage());
            saveExecutionState(state);
            
            throw new RuntimeException("Task B failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void stop() throws Exception {
        stopped = true;
        System.out.println("Stop signal received");
    }
}

// ============================================
// 4. Job XML 配置
// ============================================

/*
文件路径: src/main/resources/META-INF/batch-jobs/dataProcessingJob.xml

<?xml version="1.0" encoding="UTF-8"?>
<job id="dataProcessingJob" xmlns="https://jakarta.ee/xml/ns/jakartaee"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee 
                         https://jakarta.ee/xml/ns/jakartaee/jobXML_2_0.xsd"
     version="2.0" restartable="true">
    
    <step id="processStep">
        <batchlet ref="dataProcessBatchlet">
            <properties>
                <property name="simulateFailTask" value="#{jobParameters['simulateFailTask']}"/>
            </properties>
        </batchlet>
    </step>
</job>
*/

// ============================================
// 5. Jakarta Batch 配置
// ============================================

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchRuntime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories
public class BatchConfiguration {
    
    @Bean
    public JobOperator jobOperator() {
        return BatchRuntime.getJobOperator();
    }
}

// ============================================
// 6. REST 控制器 - 用于测试
// ============================================

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/batch")
public class BatchController {
    
    private final JobOperator jobOperator;
    private final DataItemRepository dataRepository;
    
    public BatchController(JobOperator jobOperator, 
                          DataItemRepository dataRepository) {
        this.jobOperator = jobOperator;
        this.dataRepository = dataRepository;
    }
    
    /**
     * 启动新的Job
     * @param simulateFailTask 模拟失败的任务: "A", "B", 或 null
     */
    @PostMapping("/start")
    public Map<String, Object> startJob(
            @RequestParam(required = false) String simulateFailTask) {
        
        Properties jobParams = new Properties();
        jobParams.setProperty("simulateFailTask", simulateFailTask != null ? simulateFailTask : "");
        jobParams.setProperty("timestamp", String.valueOf(System.currentTimeMillis()));
        
        try {
            long executionId = jobOperator.start("dataProcessingJob", jobParams);
            
            // 等待一下让Job开始执行
            Thread.sleep(500);
            
            Map<String, Object> response = new HashMap<>();
            response.put("executionId", executionId);
            response.put("message", "Job started");
            response.put("simulateFailTask", simulateFailTask);
            return response;
            
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            e.printStackTrace();
            return error;
        }
    }
    
    /**
     * 重启失败的Job
     */
    @PostMapping("/restart/{executionId}")
    public Map<String, Object> restartJob(@PathVariable long executionId) {
        try {
            JobExecution jobExecution = jobOperator.getJobExecution(executionId);
            Properties originalParams = jobOperator.getParameters(executionId);
            
            System.out.println("\n========================================");
            System.out.println("Restarting Job Execution: " + executionId);
            System.out.println("Original Status: " + jobExecution.getBatchStatus());
            
            // 获取Step的持久化数据
            List<StepExecution> stepExecutions = jobOperator.getStepExecutions(executionId);
            for (StepExecution step : stepExecutions) {
                Object persistedData = step.getPersistentUserData();
                System.out.println("Persisted data from step '" + step.getStepName() + "': " + persistedData);
            }
            System.out.println("========================================\n");
            
            // 清除失败模拟参数（这样重启时就不会再失败）
            originalParams.setProperty("simulateFailTask", "");
            
            long newExecutionId = jobOperator.restart(executionId, originalParams);
            
            // 等待一下让Job开始执行
            Thread.sleep(500);
            
            Map<String, Object> response = new HashMap<>();
            response.put("originalExecutionId", executionId);
            response.put("newExecutionId", newExecutionId);
            response.put("message", "Job restarted");
            return response;
            
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            e.printStackTrace();
            return error;
        }
    }
    
    /**
     * 获取Job执行状态
     */
    @GetMapping("/status/{executionId}")
    public Map<String, Object> getJobStatus(@PathVariable long executionId) {
        try {
            JobExecution jobExecution = jobOperator.getJobExecution(executionId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("executionId", executionId);
            response.put("jobName", jobExecution.getJobName());
            response.put("batchStatus", jobExecution.getBatchStatus().toString());
            response.put("exitStatus", jobExecution.getExitStatus());
            response.put("startTime", jobExecution.getStartTime());
            response.put("endTime", jobExecution.getEndTime());
            
            // 获取Step执行状态和持久化数据
            List<StepExecution> stepExecutions = jobOperator.getStepExecutions(executionId);
            List<Map<String, Object>> steps = new ArrayList<>();
            for (StepExecution step : stepExecutions) {
                Map<String, Object> stepInfo = new HashMap<>();
                stepInfo.put("stepName", step.getStepName());
                stepInfo.put("batchStatus", step.getBatchStatus().toString());
                stepInfo.put("exitStatus", step.getExitStatus());
                stepInfo.put("startTime", step.getStartTime());
                stepInfo.put("endTime", step.getEndTime());
                
                // ★★★ 关键：读取 StepContext 中保存的执行状态 ★★★
                Object persistedData = step.getPersistentUserData();
                if (persistedData instanceof ExecutionState) {
                    ExecutionState state = (ExecutionState) persistedData;
                    Map<String, Object> stateInfo = new HashMap<>();
                    stateInfo.put("lastCompletedTask", state.getLastCompletedTask());
                    stateInfo.put("failedTask", state.getFailedTask());
                    stateInfo.put("errorMessage", state.getErrorMessage());
                    stateInfo.put("timestamp", new Date(state.getTimestamp()));
                    stepInfo.put("persistedState", stateInfo);
                }
                
                steps.add(stepInfo);
            }
            response.put("steps", steps);
            
            return response;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            e.printStackTrace();
            return error;
        }
    }
    
    /**
     * 获取所有Job执行记录
     */
    @GetMapping("/executions")
    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> executions = new ArrayList<>();
        
        try {
            Set<String> jobNames = jobOperator.getJobNames();
            for (String jobName : jobNames) {
                int count = jobOperator.getJobInstanceCount(jobName);
                List<JobInstance> instances = jobOperator.getJobInstances(jobName, 0, count);
                
                for (JobInstance instance : instances) {
                    List<JobExecution> jobExecutions = jobOperator.getJobExecutions(instance);
                    for (JobExecution execution : jobExecutions) {
                        Map<String, Object> execInfo = new HashMap<>();
                        execInfo.put("executionId", execution.getExecutionId());
                        execInfo.put("instanceId", instance.getInstanceId());
                        execInfo.put("jobName", jobName);
                        execInfo.put("batchStatus", execution.getBatchStatus().toString());
                        execInfo.put("startTime", execution.getStartTime());
                        execInfo.put("endTime", execution.getEndTime());
                        
                        // 获取执行状态
                        try {
                            List<StepExecution> steps = jobOperator.getStepExecutions(execution.getExecutionId());
                            for (StepExecution step : steps) {
                                Object persistedData = step.getPersistentUserData();
                                if (persistedData instanceof ExecutionState) {
                                    ExecutionState state = (ExecutionState) persistedData;
                                    execInfo.put("lastCompletedTask", state.getLastCompletedTask());
                                    execInfo.put("failedTask", state.getFailedTask());
                                }
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                        
                        executions.add(execInfo);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting executions: " + e.getMessage());
        }
        
        Collections.reverse(executions); // 最新的在前面
        return executions;
    }
    
    /**
     * 获取业务数据状态
     */
    @GetMapping("/data")
    public Map<String, Object> getDataStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("totalItems", dataRepository.count());
        response.put("items", dataRepository.findAll());
        return response;
    }
    
    /**
     * 清空所有数据
     */
    @DeleteMapping("/reset")
    public Map<String, String> resetData() {
        dataRepository.deleteAll();
        return Collections.singletonMap("message", "All data cleared");
    }
}

// ============================================
// 7. application.properties
// ============================================

/*
# Database
spring.datasource.url=jdbc:h2:mem:batchdb
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# H2 Console
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# Jakarta Batch
spring.batch.job.enabled=false
*/

// ============================================
// 8. pom.xml 依赖
// ============================================

/*
<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    
    <!-- Jakarta Batch -->
    <dependency>
        <groupId>jakarta.batch</groupId>
        <artifactId>jakarta.batch-api</artifactId>
        <version>2.1.1</version>
    </dependency>
    <dependency>
        <groupId>com.ibm.jbatch</groupId>
        <artifactId>com.ibm.jbatch.container</artifactId>
        <version>2.1.1</version>
    </dependency>
    
    <!-- Jakarta Inject -->
    <dependency>
        <groupId>jakarta.inject</groupId>
        <artifactId>jakarta.inject-api</artifactId>
        <version>2.0.1</version>
    </dependency>
    
    <!-- Database -->
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
*/

# Jakarta Batch 断点续传系统 - 测试指南

## 核心机制：使用 StepContext 保存状态

### 关键 API

```java
// ★★★ 保存状态 ★★★
stepContext.setPersistentUserData(state);

// ★★★ 读取状态 ★★★
Object persistedData = stepContext.getPersistentUserData();
```

### 状态对象
```java
public class ExecutionState implements Serializable {
    private String lastCompletedTask;  // null, "A", "B"
    private String failedTask;         // null, "A", "B"
    private String errorMessage;
    private long timestamp;
}
```

### 存储位置
Jakarta Batch 会将 `PersistentUserData` 序列化后保存到：
- **表名**: `BATCH_STEP_EXECUTION_CONTEXT`
- **字段**: `PERSISTENT_USER_DATA` (BLOB 类型)

---

## 执行流程图

```
首次执行:
  getExecutionState() → state = new ExecutionState()
  executeTaskA()
    ├─ 业务逻辑
    ├─ commit
    └─ setPersistentUserData(state.lastCompletedTask="A")  ← 保存到数据库
  executeTaskB()
    ├─ 业务逻辑
    ├─ commit
    └─ setPersistentUserData(state.lastCompletedTask="B")  ← 保存到数据库

A失败后重启:
  getExecutionState() → state.failedTask="A", state.errorMessage="..."  ← 从数据库读取
  executeTaskA() → 重新执行
    └─ setPersistentUserData(state.lastCompletedTask="A")
  executeTaskB()
    └─ setPersistentUserData(state.lastCompletedTask="B")

B失败后重启:
  getExecutionState() → state.lastCompletedTask="A", state.failedTask="B"
  跳过 Task A
  executeTaskB() → 重新执行
    └─ setPersistentUserData(state.lastCompletedTask="B")
```

---

## 测试场景

### 场景 1: 正常执行

```bash
curl -X POST "http://localhost:8080/api/batch/start"
```

**控制台输出：**
```
============================================================
Starting Batchlet - JobExecutionId: 1
Step: processStep
============================================================
No previous execution state found, creating new one
Loaded execution state from StepContext:
  Last completed task: NONE
  Failed task: NONE

--- 要开始执行 A ---
  [Task A] Processing...
  [Task A] Saved data item: TaskA-Result
  [Task A] Business logic completed
  [Task A] Transaction committed ✓
  State persisted: ExecutionState{lastCompletedTask='A', failedTask='null', errorMessage='null', timestamp=1234567890}

--- 执行 A 完成 ---

--- 要开始执行 B ---
  [Task B] Processing...
  [Task B] Saved data item: TaskB-Result
  [Task B] Business logic completed
  [Task B] Transaction committed ✓
  State persisted: ExecutionState{lastCompletedTask='B', failedTask='null', errorMessage='null', timestamp=1234567891}

--- 执行 B 完成 ---

============================================================
Batchlet completed successfully
Final state: ExecutionState{lastCompletedTask='B', failedTask='null', errorMessage='null', timestamp=1234567891}
============================================================
```

**查看状态：**
```bash
curl -X GET "http://localhost:8080/api/batch/status/1"
```

**返回：**
```json
{
  "executionId": 1,
  "batchStatus": "COMPLETED",
  "steps": [
    {
      "stepName": "processStep",
      "batchStatus": "COMPLETED",
      "persistedState": {
        "lastCompletedTask": "B",
        "failedTask": null,
        "errorMessage": null
      }
    }
  ]
}
```

**查看业务数据：**
```bash
curl -X GET "http://localhost:8080/api/batch/data"
```

**返回：**
```json
{
  "totalItems": 2,
  "items": [
    {"id": 1, "name": "TaskA-Result", "data": "Data from Task A at ..."},
    {"id": 2, "name": "TaskB-Result", "data": "Data from Task B at ..."}
  ]
}
```

---

### 场景 2: 模拟任务 A 失败

```bash
# 清空数据
curl -X DELETE "http://localhost:8080/api/batch/reset"

# 启动Job，模拟A失败
curl -X POST "http://localhost:8080/api/batch/start?simulateFailTask=A"
```

**控制台输出：**
```
============================================================
Starting Batchlet - JobExecutionId: 1
============================================================
No previous execution state found, creating new one
Loaded execution state from StepContext:
  Last completed task: NONE
  Failed task: NONE

--- 要开始执行 A ---
  [Task A] Processing...
  [Task A] Transaction rolled back ✗
  State persisted: ExecutionState{lastCompletedTask='null', failedTask='A', errorMessage='Simulated failure in Task A', timestamp=...}
============================================================
Batchlet failed: Task A failed: Simulated failure in Task A
Current state saved: ExecutionState{lastCompletedTask='null', failedTask='A', errorMessage='Simulated failure in Task A', timestamp=...}
============================================================
```

**查看状态：**
```bash
curl -X GET "http://localhost:8080


stepContext.setPersistentUserData(state);
Object persistedData = stepContext.getPersistentUserData();

@Inject
private StepContext stepContext;

// 保存
ExecutionState state = new ExecutionState();
state.setLastCompletedTask("A");
stepContext.setPersistentUserData(state);  // ← 这里保存

// 读取
ExecutionState state = (ExecutionState) stepContext.getPersistentUserData();  // ← 这里读取
