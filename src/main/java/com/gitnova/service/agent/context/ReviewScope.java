package com.gitnova.service.agent.context;
import java.util.Set;
/**
* 为只读工具导入Files
*
* @param allowedRevisions 默认 {TARGET,BASE}
* @param changedFiles 变更的文件
* @param allowRepositorySearch 是否允许searchCode
* @return 工具接收包
* */
public record ReviewScope(Set<Revision> allowedRevisions,Set<String>changedFiles,boolean allowRepositorySearch) {}
