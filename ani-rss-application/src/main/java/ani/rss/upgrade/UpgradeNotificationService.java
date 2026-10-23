package ani.rss.upgrade;

import ani.rss.entity.Ani;
import ani.rss.enums.*;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.*;
import cn.hutool.core.util.ReflectUtil;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class UpgradeNotificationService {
    private final UpgradeRepository repository;
    public UpgradeNotificationService(UpgradeRepository repository) { this.repository=repository; }
    public record Context(String operationId,String subjectId,String title,String message) {}
    public void completed(Target target,Job job) {
        var config=ConfigUtil.snapshot(); if(!config.getUpgrade().isNotifications()) return;
        Context context=new Context(job.getId(),target.getSubjectId(),target.getTitle(),"自动化洗版完成，新版已安装，清单内旧文件已删除");
        Ani view=new Ani().setId("upgrade-event:"+job.getId()).setTitle(context.title()).setJpTitle(target.getOriginalTitle()).setBgmUrl("https://bgm.tv/subject/"+target.getSubjectId())
                .setSeason(Objects.requireNonNullElse(target.getSeason(),Integer.valueOf(1))).setMessage(true).setOva(SubjectEpisodeResolver.isMovie(target)).setCustomDownloadPath(true).setCustomDownloadPathTemplate(target.getOutputRoot())
                .setReleaseDate(Date.from(LocalDate.parse(target.getPremiereDate()).atStartOfDay(ZoneId.of(config.getUpgrade().getTimezone())).toInstant())).setCurrentEpisodeNumber(0).setTotalEpisodeNumber(0).setSubgroup("").setThemoviedbName("").setCover("").setImage(target.getCover());
        Set<NotificationTypeEnum> allowed=Set.of(NotificationTypeEnum.MAIL,NotificationTypeEnum.SERVER_CHAN,NotificationTypeEnum.SYSTEM,NotificationTypeEnum.TELEGRAM,NotificationTypeEnum.WEB_HOOK,NotificationTypeEnum.BARK);
        for(var channel:config.getNotificationConfigList()) {
            if(!Boolean.TRUE.equals(channel.getEnable())||!allowed.contains(channel.getNotificationType())||!channel.getStatusList().contains(NotificationStatusEnum.DOWNLOAD_END)) continue;
            String key="notification:"+job.getId()+":"+channel.getNotificationType()+":"+channel.getSort();
            if(repository.get("upgrade_events",key,Map.class).isPresent()) continue;
            repository.save("upgrade_events",key,job.getId(),key,"NOTIFICATION_DISPATCHED",1,context);
            try { ReflectUtil.newInstance(NotificationUtil.NOTIFICATION_MAP.get(channel.getNotificationType())).send(channel,view,context.message(),NotificationStatusEnum.DOWNLOAD_END); }
            catch(RuntimeException e) { repository.event(job.getId(),"NOTIFICATION_FAILED","成功通知发送失败；文件提交结果不受影响"); }
        }
    }
}
