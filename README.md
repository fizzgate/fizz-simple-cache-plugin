# 简易缓存(SimpleCache)

## 功能说明
   1. 本插件根据请求地址与请求参数，缓存目标接口的返回结果。

   2. 本插件默认采用机器内存作为缓存媒介，以求达到性能最优之目的，适用于单机部署。

   3. 本插件也可以使用 Redis 作为缓存媒介，需要配置相应的参数，适用于分布式部署。

   4. 本插件可配置无效数据(非业务数据)，如：多个用户使用同一个缓存。

   5. 本插件可配置缓存有效期，缓存内容长度，缓存容量与清理间隔(本地缓存)。


## 配置说明
gateway项目pom文件中引入以下依赖
```
        <dependency>
                <groupId>com.fizzgate</groupId>
                <artifactId>fizz-simple-cache-plugin</artifactId>
                <version>2.7.1</version>
        </dependency>   
```
       
                
管理后台导入以下SQL
```
insert into `tb_plugin` (`fixed_config`, `id`, `eng_name`, `chn_name`, `config`, `order`, `instruction`, `type`, `create_user`, `create_dept`, `create_time`, `update_user`, `update_time`, `status`, `is_deleted`) values(NULL,'53','kmjbCachePlugin','简易缓存插件','[{\"field\":\"useRedis\",\"label\":\"是否使用Redis作为缓存媒介\",\"component\":\"select\",\"dataType\":\"boolean\",\"desc\":\"默认：否。默认使用机器内存作为缓存媒介。\",\"default\":false,\"options\":[{\"label\":\"是\",\"value\":true},{\"label\":\"否\",\"value\":false}]},{\"field\":\"redisHost\",\"label\":\"Redis服务器地址\",\"component\":\"input\",\"dataType\":\"string\",\"default\":\"127.0.0.1\"},{\"field\":\"redisPort\",\"label\":\"Redis服务器端口\",\"component\":\"input\",\"dataType\":\"number\",\"default\":6379},{\"field\":\"redisPass\",\"label\":\"Redis服务器密码\",\"component\":\"input\",\"dataType\":\"string\",\"default\":\"\"},{\"field\":\"redisDatabase\",\"label\":\"Redis服务器数据库\",\"component\":\"input\",\"dataType\":\"number\",\"default\":0},{\"field\":\"redisTimeout\",\"label\":\"Redis连接超时时长(单位：秒)\",\"component\":\"input\",\"dataType\":\"number\",\"default\":3},{\"field\":\"cacheExpire\",\"label\":\"缓存的有效期(单位：秒)\",\"component\":\"input\",\"dataType\":\"number\",\"desc\":\"默认有效期：300秒，即5分钟。取值范围：[60, 24*3600]。\",\"default\":300},{\"field\":\"cacheLength\",\"label\":\"缓存内容长度(单位：字节)\",\"component\":\"input\",\"dataType\":\"number\",\"desc\":\"默认长度：4096，即4KB。若大于0，则当缓存内容长度超过阈值时，系统不会保存缓存内容。若小于等于0，则保存操作不受限制。\",\"default\":4096},{\"field\":\"cacheSize\",\"label\":\"缓存的最大容量\",\"component\":\"input\",\"dataType\":\"number\",\"desc\":\"默认最大容量：3万。取值范围：[100, 十万]。此参数只对机器内存媒介有效。\",\"default\":30000},{\"field\":\"cacheInterval\",\"label\":\"缓存的最小清理间隔(单位：秒)\",\"component\":\"input\",\"dataType\":\"number\",\"desc\":\"默认清理间隔：300秒，即5分钟。取值范围：[60, 600]。此参数只对机器内存媒介有效。\",\"default\":300},{\"field\":\"invalidData\",\"label\":\"无效数据(非业务数据)\",\"component\":\"textarea\",\"dataType\":\"string\",\"desc\":\"如：token，userId，sessionId 等类似数据，每个数据另起一行。\",\"default\":\"token\\\\nuserId\\\\nsessionId\"}]','100',NULL,'2','1123598821738675201','1260823335286165505','2022-05-02 18:42:13','1123598821738675201','2022-05-02 18:42:13','1','0');
```
