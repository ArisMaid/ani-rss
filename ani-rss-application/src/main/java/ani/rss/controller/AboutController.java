package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.auth.fun.IpWhitelist;
import ani.rss.commons.MavenUtils;
import ani.rss.entity.About;
import ani.rss.entity.Global;
import ani.rss.entity.web.Result;
import ani.rss.service.UpdateService;
import cn.hutool.core.thread.ThreadUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class AboutController extends BaseController {

    @Resource
    private UpdateService updateService;

    @Auth
    @Operation(summary = "查看关于信息")
    @PostMapping("/about")
    public Result<About> about() {
        return Result.success(updateService.about());
    }

    @Auth
    @Operation(summary = "停止服务")
    @PostMapping("/stop")
    public Result<Void> stop(@RequestParam("status") Integer status) {
        String s;
        if (status == 0) {
            s = "重启";
        } else if (status == 2) {
            s = "关闭";
        } else {
            return Result.error("不支持的停止状态");
        }

        MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();
        if (currentFile.isExe() && s.equals("重启")) {
            log.error("Windows 端不支持重启");
            return Result.error("Windows 端不支持重启");
        }

        log.info("正在{}", s);
        ThreadUtil.execute(() -> {
            ThreadUtil.sleep(3000);
            System.exit(status);
        });
        return Result.success("正在{}", s);
    }

    @Auth
    @Operation(summary = "更新")
    @PostMapping("/update")
    public Result<Void> update() {
        About about = updateService.about();
        try {
            updateService.update(about);
            return Result.success("更新成功, 正在重启...");
        } catch (Exception e) {
            log.info("更新失败 {} type:{}", about.getLatest(), e.getClass().getSimpleName());
            return Result.error("更新失败 {}", about.getLatest());
        }
    }

    private final IpWhitelist ipWhitelist = new IpWhitelist();

    @Operation(summary = "IP白名单测试")
    @PostMapping("/testIpWhitelist")
    public Result<Void> testIpWhitelist() {
        HttpServletRequest request = Global.REQUEST.get();
        Boolean b = ipWhitelist.apply(request);
        if (b) {
            return Result.success();
        }
        return Result.error();
    }
}
