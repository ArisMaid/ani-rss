package ani.rss.notification;

import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.ownership.OwnershipService;
import ani.rss.service.DownloadService;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class FileMoveNotification implements BaseNotification {
    /**
     * 测试
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     */
    @Override
    public void test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        List<NotificationStatusEnum> statusList = notificationConfig.getStatusList();

        Assert.isTrue(statusList.contains(NotificationStatusEnum.DOWNLOAD_END), "请设置为下载完成通知");
    }

    /**
     * 发送通知
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     * @return 是否成功
     */
    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        if (NotificationStatusEnum.DOWNLOAD_END != notificationStatusEnum) {
            log.info("文件移动 仅支持下载完成通知");
            return true;
        }

        // 首先就要深度克隆 防止影响原订阅设置
        ani = ObjectUtil.clone(ani);

        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);
        OwnershipService ownershipService = SpringUtil.getBean(OwnershipService.class);

        // 新的位置; 设置自定义下载位置同时启用, 用以获取新的位置
        Boolean ova = ani.getOva();
        String fileMoveTarget = notificationConfig.getFileMoveTarget();
        String fileMoveOvaTarget = notificationConfig.getFileMoveOvaTarget();
        boolean fileMoveDeleteOldEpisode = notificationConfig.getFileMoveDeleteOldEpisode();
        if (ova) {
            ani.setDownloadPath(fileMoveOvaTarget);
        } else {
            ani.setDownloadPath(fileMoveTarget);
        }
        ani.setCustomDownloadPath(true);

        String target = downloadService.getDownloadPath(ani);
        if (fileMoveDeleteOldEpisode) {
            log.warn("已忽略无法证明归属的目标目录洗版设置 subscriptionId:{}", ani.getId());
        }
        if (Boolean.TRUE.equals(notificationConfig.getFileMoveCopyModel())) {
            int copied = ownershipService.copySubscriptionFiles(ani.getId(), target);
            log.info("已复制归属文件 subscriptionId:{} count:{}", ani.getId(), copied);
        } else {
            ownershipService.moveSubscriptionFiles(ani.getId(), target);
            log.info("已移动归属文件 subscriptionId:{}", ani.getId());
        }
        return true;
    }
}
