package com.webadmin.application.survey.port;

/**
 * 文件存储端口（文件本体）。
 *
 * <p>刻意与 {@link SurveyFilePort}（元数据）分开：元数据在数据库里，
 * 文件本体可能在本地磁盘 / OSS / COS。合成一个端口后，
 * "换存储介质"会牵动数据库事务边界 —— 那是两件不同介质上的事。
 *
 * <p>换 OSS/COS 时的改法：新增一个实现类，配置里切换；
 * 应用层与数据库结构都不用动（{@code srvy_attachment.storage_type} 已预留区分）。
 */
public interface FileStoragePort {

    /**
     * 保存字节流。
     *
     * @param content      文件内容
     * @param relativePath 相对路径，形如 {@code 1/COLLECT_FILE/2026-09/uuid.csv}
     * @return 实际写入的相对路径（相对存储根）
     */
    String store(byte[] content, String relativePath);

    /** 读取文件内容。文件不存在时抛 {@code BizException(SurveyErrorCode.FILE_NOT_FOUND)}。 */
    byte[] read(String relativePath);

    /** 删除文件（保存元数据失败时的补偿清理）。 */
    void delete(String relativePath);
}
