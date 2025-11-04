import jakarta.batch.api.partition.PartitionMapper;
import jakarta.batch.api.partition.PartitionPlan;
import jakarta.batch.api.partition.PartitionPlanImpl;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Named;
import java.util.Properties;

@Dependent
@Named("myPartitionMapper")
public class MyPartitionMapper implements PartitionMapper {
    @Override
    public PartitionPlan mapPartitions() {
        int n = 3;

        Properties[] partProps = new Properties[n];
        for (int i = 0; i < n; i++) {
            Properties p = new Properties();
            p.setProperty("shardId", String.valueOf(i));
            p.setProperty("range", i + "-" + (i + 1));
            partProps[i] = p;
        }

        PartitionPlanImpl plan = new PartitionPlanImpl();
        plan.setPartitions(n);                 // 分区数
        plan.setThreads(n);                   // 并行线程数
        plan.setPartitionProperties(partProps); // 每分区 properties（长度必须与 partitions 相同）
        // 可选：重启时是否覆盖之前的分区数
        // plan.setPartitionsOverride(true);

        return plan;
    }
}


import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Dependent
@Named("myBatchlet")
public class MyBatchlet extends AbstractBatchlet {

    @Inject @BatchProperty(name = "shardId")
    private String shardId;

    @Inject @BatchProperty(name = "range")
    private String range;

    @Override
    public String process() throws Exception {
        System.out.printf("shardId=%s, range=%s%n", shardId, range);
        return "COMPLETED";
    }
}


<step id="partitionedStep">
  <partition>
    <mapper ref="myPartitionMapper"/>
  </partition>
  <batchlet ref="myBatchlet"/>
</step>
