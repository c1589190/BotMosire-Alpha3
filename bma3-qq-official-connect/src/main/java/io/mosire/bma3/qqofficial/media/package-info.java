/**
 * QQ 官方本地媒体直传边界：dataDir 内常规文件、5MiB 上限、标准 Base64、固定 files 上传
 * {@code srv_send_msg=false}、随后单次 {@code msg_type=7}，消息板 PENDING 先行，UNKNOWN 不重发。
 *
 * <p>本阶段不实现大文件分片上传或消息撤回；工具工厂存在但尚未注册。</p>
 */
package io.mosire.bma3.qqofficial.media;
