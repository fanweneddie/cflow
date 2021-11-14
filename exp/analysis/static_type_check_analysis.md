# static type check analysis

[toc]

I test the effect of static type check and have found 77 differences.

Here I have two versions of `cflow`: 

* version `revised` leverages `UniqueStmt` to guarantee determinism of output
*  version `type_check` is based on `revised` and has implemented a check of declared type of base object in an invoke statement.

On both versions of `cflow`, I run command

```
$ ./run.sh -a hadoop_common -s
```

and compare the output of them.

I use command `diff` to compare and **I find that there are 77 cases of difference.**

Since the output file of `diff` is so large(more than 7000 lines), it is hard to carefully read each line one by one.

I use a trick to check whether the change is caused by polymorphism. If taint `t2` is taint `t1`'s successor which satisfies:

* `t2`'s taint transfer type is return 
* `t1`'s taint transfer type is not call
* `t1`'s method is different from `t2`'s method of invoke statement,

then we can say the polymorphism occurs here and affects taint flow.

For example, in this case:

```
t1: r1 in stmt ... in method <A: void foo()>
t2: [Return] r0 in stmt r0.<A: void foo()> in method ... 
```

Taint transfer from `t1` to  `t2` is not caused by polymorphism.

But in this case: 

```
t1: r1 in stmt ... in method <A: void foo()>
t2: [Return] r0 in stmt r0.<B: void foo()> in method ... 
```

Taint transfer from `t1` to  `t2` is caused by polymorphism, since the call site is `r0.<B: void foo()>` but the invoked method is `<A: void foo()>`.



Those 77 changes are divided mainly in three types:

1. False-positives caused by **imprecise callee** in version `revised` have been eliminated by type checking() (65 + 3 = 68 cases)
2. False-negative caused by **taint wrapper** in version `revised` have appeared by type checking(1 case)
3. True-positives in version `revised` have been eliminated by the **strict type checking**(8 cases)

And I will demonstrate the first two types in the following part.(The third type is demonstrated in the analysis of points-to analysis)

## 1. Elimination of false-positives caused by imprecise callee

Imprecise callee in version `revised` can cause some false-positive cases. That is, it can generate some false positive path segments or even get false-positive sink taints. However, static type check can help solve some of those problems.

### 1.1  Eliminate false-positive sink taints

I collect sink taints that are caused by polymorphism using the trick above. I find that there are 73 sink taints that appear in the output of version `revised` and disappear in the output of version `type_check`, where originally 65 of them are false-positive sinks and 8 of them are true-positive sinks. 

Those 8 originally true-positive sinks are removed in version `type_check` due to its strict type check, and this problem can be solved by implementing a points-to analysis. 

Those 73 sinks are shown at the end of this files.

### 1.2 Eliminate false-positive path segments

Some segments of paths are generated due to the imprecise callee. Those path segments do not generate new sink taints. Here I show 3 cases:

#### 1.2.1

Taints below are caused by imprecise callee.

```
<     -> [Call] r1.<org.apache.hadoop.fs.Stat: long blockSize> in $r2 = virtualinvoke r1.<org.apache.hadoop.util.Shell: java.lang.String[] getExecString()>() in method <org.apache.hadoop.util.Shell: void runCommand()>
<     -> [Return] r1.<org.apache.hadoop.fs.Stat: long blockSize> in $r2 = virtualinvoke r1.<org.apache.hadoop.util.Shell: java.lang.String[] getExecString()>() in method <org.apache.hadoop.util.Shell: void runCommand()>
```

Position: line 3059, 3060 and 3083, 3084 and 3109 , 3110 and 3135, 3136 and 3160, 3161 and 3185, 3186 and 3208, 3209

Reason: method `<org.apache.hadoop.util.Shell: java.lang.String[] getExecString()>()` is an abstract method without body. Since `type_check` analyzes that `r1` is an object of class `Util`, `type_check` will not propagate taint into `getExecString` since it lacks method body. 

However, `revised` can find other methods of subclasses such as `<org.apache.hadoop.fs.Stat: java.lang.String[] getExecString()>` and `<org.apache.hadoop.util.Shell$ShellCommandExecutor: java.lang.String[] getExecString()>`, `<org.apache.hadoop.fs.DU$DUShell: java.lang.String[] getExecString()>` , this taint can be propagated into those callee methods and thus generates a call taint and a return taint.

### 1.2.2

Case 1.2.2 is similar to case 1.2.1.

Taint is 

```
<     -> [Call] r1.<org.apache.hadoop.util.Shell: long lastTime> in $r2 = virtualinvoke r1.<org.apache.hadoop.util.Shell: java.lang.String[] getExecString()>() in method <org.apache.hadoop.util.Shell: void runCommand()>
<     -> [Return] r1.<org.apache.hadoop.util.Shell: long lastTime> in $r2 = virtualinvoke r1.<org.apache.hadoop.util.Shell: java.lang.String[] getExecString()>() in method <org.apache.hadoop.util.Shell: void runCommand()>
```

at line 4023, 4024 and 4048, 4049 and 4075, 4076 and 4102, 4103 and 4128, 4129 and 4154, 4155 and 4178, 4179

#### 1.2.3

Case 1.2.3 is also similar.

```
<     -> r0.<org.apache.hadoop.fs.FileSystem$6: java.lang.String val$scheme> in r0.<org.apache.hadoop.fs.FileSystem$6: java.lang.String val$scheme> = r1 in method <org.apache.hadoop.fs.FileSystem$6: void <init>(java.lang.String,org.apache.hadoop.fs.FileSystem$Statistics)>
<     -> [Return] $r8.<org.apache.hadoop.fs.FileSystem$6: java.lang.String val$scheme> in specialinvoke $r8.<org.apache.hadoop.fs.FileSystem$6: void <init>(java.lang.String,org.apache.hadoop.fs.FileSystem$Statistics)>(r0, r5) in method <org.apache.hadoop.fs.FileSystem: org.apache.hadoop.fs.FileSystem$Statistics getStatistics(java.lang.String,java.lang.Class)>
<     -> [Call] $r8.<org.apache.hadoop.fs.FileSystem$6: java.lang.String val$scheme> in virtualinvoke $r9.<org.apache.hadoop.fs.GlobalStorageStatistics: org.apache.hadoop.fs.StorageStatistics put(java.lang.String,org.apache.hadoop.fs.GlobalStorageStatistics$StorageStatisticsProvider)>(r0, $r8) in method <org.apache.hadoop.fs.FileSystem: org.apache.hadoop.fs.FileSystem$Statistics getStatistics(java.lang.String,java.lang.Class)>
<     -> [Call] r5.<org.apache.hadoop.fs.FileSystem$6: java.lang.String val$scheme> in r25 = interfaceinvoke r5.<org.apache.hadoop.fs.GlobalStorageStatistics$StorageStatisticsProvider: org.apache.hadoop.fs.StorageStatistics provide()>() in method <org.apache.hadoop.fs.GlobalStorageStatistics: org.apache.hadoop.fs.StorageStatistics put(java.lang.String,org.apache.hadoop.fs.GlobalStorageStatistics$StorageStatisticsProvider)>
<     -> $r3 in $r3 = r1.<org.apache.hadoop.fs.FileSystem$6: java.lang.String val$scheme> in method <org.apache.hadoop.fs.FileSystem$6: org.apache.hadoop.fs.StorageStatistics provide()>
<     -> [Call] $r3 in specialinvoke $r0.<org.apache.hadoop.fs.FileSystemStorageStatistics: void <init>(java.lang.String,org.apache.hadoop.fs.FileSystem$Statistics)>($r3, $r2) in method <org.apache.hadoop.fs.FileSystem$6: org.apache.hadoop.fs.StorageStatistics provide()>
<     -> [Call] r1 in specialinvoke r0.<org.apache.hadoop.fs.StorageStatistics: void <init>(java.lang.String)>(r1) in method <org.apache.hadoop.fs.FileSystemStorageStatistics: void <init>(java.lang.String,org.apache.hadoop.fs.FileSystem$Statistics)>
<     -> r0.<org.apache.hadoop.fs.StorageStatistics: java.lang.String name> in r0.<org.apache.hadoop.fs.StorageStatistics: java.lang.String name> = r1 in method <org.apache.hadoop.fs.StorageStatistics: void <init>(java.lang.String)>
<     -> [Return] r0.<org.apache.hadoop.fs.StorageStatistics: java.lang.String name> in specialinvoke r0.<org.apache.hadoop.fs.StorageStatistics: void <init>(java.lang.String)>(r1) in method <org.apache.hadoop.fs.FileSystemStorageStatistics: void <init>(java.lang.String,org.apache.hadoop.fs.FileSystem$Statistics)>
<     -> [Return] $r0.<org.apache.hadoop.fs.StorageStatistics: java.lang.String name> in specialinvoke $r0.<org.apache.hadoop.fs.FileSystemStorageStatistics: void <init>(java.lang.String,org.apache.hadoop.fs.FileSystem$Statistics)>($r3, $r2) in method <org.apache.hadoop.fs.FileSystem$6: org.apache.hadoop.fs.StorageStatistics provide()>
<     -> [Return] r25.<org.apache.hadoop.fs.StorageStatistics: java.lang.String name> in r25 = interfaceinvoke r5.<org.apache.hadoop.fs.GlobalStorageStatistics$StorageStatisticsProvider: org.apache.hadoop.fs.StorageStatistics provide()>() in method <org.apache.hadoop.fs.GlobalStorageStatistics: org.apache.hadoop.fs.StorageStatistics put(java.lang.String,org.apache.hadoop.fs.GlobalStorageStatistics$StorageStatisticsProvider)>
<     -> [Call] r25.<org.apache.hadoop.fs.StorageStatistics: java.lang.String name> in $r6 = virtualinvoke r25.<org.apache.hadoop.fs.StorageStatistics: java.lang.String getName()>() in method <org.apache.hadoop.fs.GlobalStorageStatistics: org.apache.hadoop.fs.StorageStatistics put(java.lang.String,org.apache.hadoop.fs.GlobalStorageStatistics$StorageStatisticsProvider)>
<     -> [Return] r25.<org.apache.hadoop.fs.StorageStatistics: java.lang.String name> in $r6 = virtualinvoke r25.<org.apache.hadoop.fs.StorageStatistics: java.lang.String getName()>() in method <org.apache.hadoop.fs.GlobalStorageStatistics: org.apache.hadoop.fs.StorageStatistics put(java.lang.String,org.apache.hadoop.fs.GlobalStorageStatistics$StorageStatisticsProvider)>
<     -> [Call] r25.<org.apache.hadoop.fs.StorageStatistics: java.lang.String name> in $r14 = virtualinvoke r25.<org.apache.hadoop.fs.StorageStatistics: java.lang.String getName()>() in method <org.apache.hadoop.fs.GlobalStorageStatistics: org.apache.hadoop.fs.StorageStatistics put(java.lang.String,org.apache.hadoop.fs.GlobalStorageStatistics$StorageStatisticsProvider)>
<     -> $r1 in $r1 = r0.<org.apache.hadoop.fs.StorageStatistics: java.lang.String name> in method <org.apache.hadoop.fs.StorageStatistics: java.lang.String getName()>
<     -> [Return] $r14 in $r14 = virtualinvoke r25.<org.apache.hadoop.fs.StorageStatistics: java.lang.String getName()>() in method <org.apache.hadoop.fs.GlobalStorageStatistics: org.apache.hadoop.fs.StorageStatistics put(java.lang.String,org.apache.hadoop.fs.GlobalStorageStatistics$StorageStatisticsProvider)>
```

at line 6893 to 6908 and 25046 to 25061

## 2. Elimination of false-negatives caused by taint wrapper

Some changes detected are caused by the removal of taint wrapper in  version`type_check`. Without taint wrapper, version `taint_Check` can further explore taints in those callee. Therefore, it can have more true-positives.

```
-- Sink r0.<org.apache.hadoop.util.Shell$1: org.apache.hadoop.util.Shell this$0> in $z0 = virtualinvoke r0.<org.apache.hadoop.util.Shell$1: boolean isInterrupted()>() in method <org.apache.hadoop.util.Shell$1: void run()> along:
    ...
    -> [Call] r24.<org.apache.hadoop.util.Shell$1: org.apache.hadoop.util.Shell this$0> in virtualinvoke r24.<java.lang.Thread: void start()>() in method <org.apache.hadoop.util.Shell: void runCommand()>
    -> r0.<org.apache.hadoop.util.Shell$1: org.apache.hadoop.util.Shell this$0> in $z0 = virtualinvoke r0.<org.apache.hadoop.util.Shell$1: boolean isInterrupted()>() in method <org.apache.hadoop.util.Shell$1: void run()>
```

Position: line 3160 to 3181

Reason: For `revised`, when `<java.lang.Thread: void start()>()` is called, it is analyzed by a taint wrapper. So no further taint is propagated. 

However, for `type_checker`, it detects that base object `r24` is an object of `org.apache.hadoop.util.Shell$1` and the callee method is `<org.apache.hadoop.util.Shell$1: boolean isInterrupted()>()`, which is not in the list of taint wrapper. So it can continue propagating.



## Plus

73 false positive sink taints

```
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i12) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeInt(int)>($i27) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i31) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeInt(int)>($i20) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink $r5 in virtualinvoke $r5.<java.io.DataOutputStream: void write(byte[],int,int)>($r6, 0, $i0) in method <org.apache.hadoop.io.SequenceFile$BlockCompressWriter: void writeBuffer(org.apache.hadoop.io.DataOutputBuffer)> along:
< -- Sink $r16 in specialinvoke $r8.<java.lang.AssertionError: void <init>(java.lang.Object)>($r16) in method <org.apache.hadoop.service.AbstractService: org.apache.hadoop.service.Service$STATE enterState(org.apache.hadoop.service.Service$STATE)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeShort(int)>($i16) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i17) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i11) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i37) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink $r13 in virtualinvoke $r13.<java.io.DataOutputStream: void flush()>() in method <org.apache.hadoop.io.SequenceFile$RecordCompressWriter: void append(java.lang.Object,java.lang.Object)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeShort(int)>($i21) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r1 in $r3 = interfaceinvoke r2.<com.google.common.cache.LoadingCache: java.lang.Object getUnchecked(java.lang.Object)>(r1) in method <org.apache.hadoop.io.compress.CodecPool: void updateLeaseCount(com.google.common.cache.LoadingCache,java.lang.Object,int)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i35) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i34) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r1.<org.apache.hadoop.util.JvmPauseMonitor$Monitor: org.apache.hadoop.util.JvmPauseMonitor this$0> in specialinvoke r0.<java.lang.Thread: void <init>(java.lang.Runnable)>(r1) in method <org.apache.hadoop.util.Daemon: void <init>(java.lang.Runnable)> along:
< -- Sink r31 in virtualinvoke r31.<java.io.InputStream: void close()>() in method <org.apache.hadoop.security.KDiag: int run(java.lang.String[])> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i18) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeLong(long)>(l0) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeInt(int)>($i24) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeShort(int)>($i30) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r1.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r1.<java.io.DataOutput: void write(byte[],int,int)>(r4, 0, i0) in method <org.apache.hadoop.io.file.tfile.Utils: void writeString(java.io.DataOutput,java.lang.String)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i32) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r2.<org.apache.hadoop.io.file.tfile.Chunk$SingleChunkEncoder: java.io.DataOutputStream out> in specialinvoke r0.<java.io.DataOutputStream: void <init>(java.io.OutputStream)>(r2) in method <org.apache.hadoop.io.file.tfile.TFile$Writer$ValueRegister: void <init>(org.apache.hadoop.io.file.tfile.TFile$Writer,java.io.OutputStream)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i26) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r3.<org.apache.hadoop.registry.client.impl.zk.CuratorService$1: org.apache.hadoop.registry.client.impl.zk.PathListener val$listener> in interfaceinvoke $r5.<org.apache.curator.framework.listen.Listenable: void addListener(java.lang.Object)>(r3) in method <org.apache.hadoop.registry.client.impl.zk.CuratorService: org.apache.hadoop.registry.client.impl.zk.ListenerHandle registerPathListener(org.apache.hadoop.registry.client.impl.zk.PathListener)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeInt(int)>($i14) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in $i0 = virtualinvoke r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: int size()>() in method <org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: long getRawSize()> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i22) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i11) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeShort(int)>($s0) in method <org.apache.hadoop.io.file.tfile.Utils$Version: void write(java.io.DataOutput)> along:
< -- Sink r2.<org.apache.hadoop.io.file.tfile.Chunk$ChunkEncoder: java.io.DataOutputStream out> in specialinvoke r0.<java.io.DataOutputStream: void <init>(java.io.OutputStream)>(r2) in method <org.apache.hadoop.io.file.tfile.TFile$Writer$ValueRegister: void <init>(org.apache.hadoop.io.file.tfile.TFile$Writer,java.io.OutputStream)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeShort(int)>($i33) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i34) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink $r2 in virtualinvoke $r2.<java.lang.Thread: void interrupt()>() in method <org.apache.hadoop.util.JvmPauseMonitor: void serviceStop()> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i28) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.util.Daemon: java.lang.Runnable runnable> in virtualinvoke r0.<org.apache.hadoop.util.Daemon: void setName(java.lang.String)>($r2) in method <org.apache.hadoop.util.Daemon: void <init>(java.lang.Runnable)> along:
< -- Sink $r20 in r31 = virtualinvoke $r20.<java.lang.ClassLoader: java.io.InputStream getResourceAsStream(java.lang.String)>(r9) in method <org.apache.hadoop.security.KDiag: int run(java.lang.String[])> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.TFile$Writer$ValueRegister: org.apache.hadoop.io.file.tfile.TFile$Writer this$0> in specialinvoke r0.<java.io.DataOutputStream: void <init>(java.io.OutputStream)>(r2) in method <org.apache.hadoop.io.file.tfile.TFile$Writer$ValueRegister: void <init>(org.apache.hadoop.io.file.tfile.TFile$Writer,java.io.OutputStream)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeShort(int)>($i30) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i35) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeInt(int)>($i24) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i31) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeShort(int)>($i21) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeInt(int)>($i27) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i17) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink $r3 in virtualinvoke $r3.<java.lang.Thread: void start()>() in method <org.apache.hadoop.util.JvmPauseMonitor: void serviceStart()> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeShort(int)>($i16) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink $r23 in virtualinvoke $r23.<java.io.DataOutputStream: void flush()>() in method <org.apache.hadoop.io.SequenceFile$Writer: void append(java.lang.Object,java.lang.Object)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeInt(int)>($i20) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i28) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r7.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r7.<java.io.DataOutput: void write(byte[],int,int)>($r14, 0, $i4) in method <org.apache.hadoop.io.file.tfile.TFile$TFileIndex: void write(java.io.DataOutput)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeShort(int)>($s1) in method <org.apache.hadoop.io.file.tfile.Utils$Version: void write(java.io.DataOutput)> along:
< -- Sink $r29 in specialinvoke $r28.<java.io.DataOutputStream: void <init>(java.io.OutputStream)>($r29) in method <org.apache.hadoop.io.SequenceFile$Writer: void init(org.apache.hadoop.conf.Configuration,org.apache.hadoop.fs.FSDataOutputStream,boolean,java.lang.Class,java.lang.Class,org.apache.hadoop.io.compress.CompressionCodec,org.apache.hadoop.io.SequenceFile$Metadata,int)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i12) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i25) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink $r30 in specialinvoke $r29.<java.io.BufferedOutputStream: void <init>(java.io.OutputStream)>($r30) in method <org.apache.hadoop.io.SequenceFile$Writer: void init(org.apache.hadoop.conf.Configuration,org.apache.hadoop.fs.FSDataOutputStream,boolean,java.lang.Class,java.lang.Class,org.apache.hadoop.io.compress.CompressionCodec,org.apache.hadoop.io.SequenceFile$Metadata,int)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.TFile$Writer$KeyRegister: org.apache.hadoop.io.file.tfile.TFile$Writer this$0> in specialinvoke r0.<java.io.DataOutputStream: void <init>(java.io.OutputStream)>($r2) in method <org.apache.hadoop.io.file.tfile.TFile$Writer$KeyRegister: void <init>(org.apache.hadoop.io.file.tfile.TFile$Writer,int)> along:
< -- Sink $r7 in virtualinvoke $r7.<java.io.DataOutputStream: void flush()>() in method <org.apache.hadoop.io.SequenceFile$BlockCompressWriter: void writeBuffer(org.apache.hadoop.io.DataOutputBuffer)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i22) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink $r3 in virtualinvoke $r3.<java.lang.Thread: void join()>() in method <org.apache.hadoop.util.JvmPauseMonitor: void serviceStop()> along:
< -- Sink r31 in virtualinvoke r31.<java.io.InputStream: void close()>() in method <org.apache.hadoop.security.KDiag: int run(java.lang.String[])> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeInt(int)>($i14) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i18) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink $r2 in virtualinvoke r0.<org.apache.hadoop.util.Daemon: void setName(java.lang.String)>($r2) in method <org.apache.hadoop.util.Daemon: void <init>(java.lang.Runnable)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i37) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r31 in virtualinvoke r31.<java.io.InputStream: void close()>() in method <org.apache.hadoop.security.KDiag: int run(java.lang.String[])> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeLong(long)>(l0) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r31 in virtualinvoke r31.<java.io.InputStream: void close()>() in method <org.apache.hadoop.security.KDiag: int run(java.lang.String[])> along:
< -- Sink r7.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r7.<java.io.DataOutput: void write(byte[],int,int)>($r8, 0, $i2) in method <org.apache.hadoop.io.file.tfile.TFile$TFileIndex: void write(java.io.DataOutput)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i25) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0 in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i26) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
< -- Sink r0.<org.apache.hadoop.io.file.tfile.BCFile$Writer$BlockAppender: org.apache.hadoop.io.file.tfile.BCFile$Writer$WBlockState wBlkState> in interfaceinvoke r0.<java.io.DataOutput: void writeByte(int)>($i32) in method <org.apache.hadoop.io.file.tfile.Utils: void writeVLong(java.io.DataOutput,long)> along:
```

