<template>
  <section class="upgrade-page app-page-layout" v-loading="loading">
    <PageHeaderView title="洗版" :subtitle="tabDescriptions[tab]">
      <template #actions><el-button class="auto-button" icon="Refresh" :disabled="loading" @click="reload">刷新</el-button></template>
    </PageHeaderView>
    <div class="upgrade-content app-page-content app-page-padding">
    <el-tabs v-model="tab" class="segmented-tabs upgrade-tabs">
      <el-tab-pane label="概览" name="overview">
        <el-scrollbar class="upgrade-scrollbar" always><div class="tab-scroll-content">
        <section class="settings-section"><div class="settings-section-heading"><h3>运行模式</h3><span class="hint">同一时间只启用一种洗版模式</span></div>
        <div class="mode-controls">
      <el-switch :model-value="status.mode?.mode === 'LEGACY'" :disabled="busy" active-text="原版洗版" aria-label="原版洗版" @change="value => setMode(value ? 'LEGACY' : 'OFF')"/>
      <el-switch :model-value="status.mode?.mode === 'AUTOMATIC'" :disabled="busy" active-text="自动化洗版" aria-label="自动化洗版" @change="value => setMode(value ? 'AUTOMATIC' : 'OFF')"/>
      <span v-if="status.commitInProgress">正在完成文件提交，模式切换后当前提交仍会收敛。</span>
        </div>
        <el-alert title="新版验证并安装成功后立即删除被替换的旧版，无法从本地恢复。文件移动与 OpenList 上传通知不适用于自动洗版。" type="warning" :closable="false" show-icon/>
        </section>
        <section class="settings-section"><div class="settings-section-heading"><h3>执行概况</h3><el-button :loading="busy" @click="exportPreview()">导出只读决策预演</el-button></div>
        <div class="summary-grid">
          <el-card v-for="(value, key) in status.jobs || {}" :key="key" shadow="never"><strong>{{ value }}</strong><div>{{ label(key) }}</div></el-card>
        </div>
        <p>ffprobe：{{ status.dependencies?.ffprobe ? '可用' : '未检测到' }} · ffmpeg：{{ status.dependencies?.ffmpeg ? '可用' : '未检测到' }} · AI：{{ status.aiEnabled ? '已启用' : '未启用，规则引擎仍可使用' }}</p>
        <p>今日 AI：{{ status.aiUsage?.requests || 0 }} 次请求 / {{ settings?.aiDailyRequests }} 上限，{{ status.aiUsage?.tokens || 0 }} token / {{ settings?.aiDailyTokens }} 上限。连接自检：{{ status.aiTested ? '已通过' : '待配置或自检' }}</p>
        <p>全量目标：{{ status.coverageMetrics?.totalTargets || 0 }}。以下比例均使用全量目标为分母，暂停、无资源和待确认目标也计入。</p>
        <el-table :data="coverageRows" empty-text="尚无目标统计"><el-table-column prop="label" label="覆盖与阻塞"/><el-table-column prop="count" label="涉及作品数"/><el-table-column prop="rate" label="全量占比"/></el-table>
        </section><section class="settings-section"><div class="settings-section-heading"><h3>目录与媒体库扫描</h3></div>
        <el-table :data="status.scanCursors || []" empty-text="尚未扫描目录或媒体库">
          <el-table-column prop="id" label="扫描范围" min-width="220"/>
          <el-table-column prop="offset" label="已处理游标" width="130"/>
          <el-table-column label="覆盖状态" width="150"><template #default="{row}">{{ row.coverageIncomplete ? '上游受限，覆盖未完整' : row.complete ? '本次扫描完成' : '尚未完成' }}</template></el-table-column>
          <el-table-column prop="error" label="错误" min-width="200"/>
        </el-table>
        </section></div></el-scrollbar>
      </el-tab-pane>
      <el-tab-pane label="季度订阅" name="campaigns">
        <el-scrollbar class="upgrade-scrollbar" always><div class="tab-scroll-content">
        <section class="settings-section"><div class="settings-section-heading"><div><h3>Mikan 季度订阅</h3><p class="hint">选择年份和季度，按首播日期发现作品并持续寻找更好的版本。</p></div><el-button type="primary" icon="Plus" @click="newCampaign">新增季度订阅</el-button></div>
        <el-table :data="campaignRows" empty-text="请先保存质量规则，再添加季度订阅；可先仅监测资源">
          <el-table-column prop="name" label="任务"/>
          <el-table-column label="订阅季度" width="140"><template #default="{row}">{{ row.catalogSource === 'MIKAN' ? `${row.mikanYear} 年 · ${row.mikanSeason}季` : '自定义日期（已有）' }}</template></el-table-column>
          <el-table-column label="首播区间" min-width="210"><template #default="{row}">{{ row.startDate }} 至 {{ row.endDate }}</template></el-table-column>
          <el-table-column prop="priority" label="任务优先级" width="110"/>
          <el-table-column label="状态" width="90"><template #default="{row}">{{ row.enabled ? '启用' : '暂停' }}</template></el-table-column>
          <el-table-column label="媒体库" min-width="140"><template #default="{row}">{{ !row.libraryId || row.libraryId === 'UNASSIGNED' ? '仅订阅监测' : row.libraryId }}</template></el-table-column>
          <el-table-column label="操作" min-width="230"><template #default="{row}">
            <el-button text @click="editCampaign(row)">编辑</el-button>
            <el-button text @click="action(`/campaigns/${row.id}/scan`)">立即扫描</el-button>
            <el-button text @click="toggleCampaign(row)">{{ row.enabled ? '暂停' : '启用' }}</el-button>
          </template></el-table-column>
        </el-table>
        </section></div></el-scrollbar>
      </el-tab-pane>
      <el-tab-pane label="质量规则" name="policies">
        <el-scrollbar class="upgrade-scrollbar" always><div class="tab-scroll-content">
        <section class="settings-section"><div class="settings-section-heading"><div><h3>质量规则</h3><p class="hint">按顺序比较维度，同一规则可供多个订阅使用。</p></div><el-button type="primary" icon="Plus" @click="editPolicy()">新增规则</el-button></div>
        <el-table :data="policyRows" empty-text="尚未保存规则；默认模板需明确保存后使用">
          <el-table-column prop="name" label="规则"/>
          <el-table-column label="维度优先级" min-width="320"><template #default="{row}">{{ row.dimensions.map(d => dimensionLabels[d]).join(' > ') }}</template></el-table-column>
          <el-table-column prop="minimumResolution" label="最低分辨率"/>
          <el-table-column label="操作" min-width="180"><template #default="{row}">
            <el-button text :disabled="busy" @click="editPolicy(row)">编辑规则</el-button>
            <el-button text type="danger" :disabled="busy" @click="deletePolicy(row)">删除</el-button>
          </template></el-table-column>
        </el-table>
        </section></div></el-scrollbar>
      </el-tab-pane>
      <el-tab-pane label="作品与候选" name="targets">
        <el-scrollbar class="upgrade-scrollbar" always><div class="tab-scroll-content">
        <div class="settings-section-heading"><h3>作品档案</h3><span class="hint">查看候选、映射与持久排除</span></div>
        <el-table :data="targetPage.items || []" empty-text="目录发现后将在这里显示作品">
          <el-table-column prop="title" label="作品" min-width="200"/>
          <el-table-column prop="type" label="类型" width="100"/>
          <el-table-column prop="premiereDate" label="首播日期" width="130"/>
          <el-table-column label="状态" width="130"><template #default="{row}">{{ row.excluded ? '已排除' : label(row.state) }}</template></el-table-column>
          <el-table-column label="生效日期任务" min-width="180"><template #default="{row}">{{ campaignRows.find(c => c.id === row.campaignId)?.name || row.campaignId || '未生效' }}</template></el-table-column>
          <el-table-column label="操作" min-width="200"><template #default="{row}">
            <el-button text @click="showCandidates(row)">查看候选</el-button>
            <el-button text @click="editTarget(row)">映射与规则</el-button>
          </template></el-table-column>
        </el-table>
        <div class="pager"><el-button :disabled="targetOffset === 0" @click="targetOffset -= 50; loadTargets()">上一页</el-button><el-button :disabled="!targetPage.hasMore" @click="targetOffset += 50; loadTargets()">下一页</el-button></div>
        <template v-if="selectedTarget">
          <h3>{{ selectedTarget.title }} 的候选</h3><el-button :loading="busy" @click="exportPreview(selectedTarget.id)">导出本作品决策预演</el-button>
          <p v-if="targetCoverage" class="hint">章节覆盖：{{ targetCoverage.expectedKnown ? `${targetCoverage.expectedChapters.length} 个已播章节` : '章节资料待补充' }}；{{ targetCoverage.sourceScanComplete ? '历史来源扫描完成' : '来源扫描尚未完整' }}。尚缺 {{ targetCoverage.missingChapters.length }} 个章节；{{ targetCoverage.waitingEvidence }} 个资源待补证，{{ targetCoverage.identityConflicts }} 个资源身份不符。覆盖不等同于下载或入库完成。</p>
          <el-table :data="versionPage.items || []" empty-text="尚无已接管或已安装版本">
            <el-table-column prop="path" label="已有版本路径" min-width="320"/>
            <el-table-column prop="origin" label="来源" width="180"/>
            <el-table-column label="状态" width="100"><template #default="{row}">{{ row.active ? '现有版本' : '已替换' }}</template></el-table-column>
            <el-table-column label="保护" width="120"><template #default="{row}"><el-switch :model-value="row.protectedVersion" :disabled="!row.active" @change="value => protect(row, value)"/></template></el-table-column>
          </el-table>
          <div class="pager"><el-button :disabled="versionOffset === 0" @click="versionOffset -= 50; loadVersions()">已有版本上一页</el-button><el-button :disabled="!versionPage.hasMore" @click="versionOffset += 50; loadVersions()">已有版本下一页</el-button></div>
          <el-table :data="candidatePage.items || []">
            <el-table-column prop="title" label="发布标题" min-width="300"/>
            <el-table-column label="结果" width="150"><template #default="{row}">{{ label(row.state) }}</template></el-table-column>
            <el-table-column prop="reason" label="比较解释与下一动作" min-width="280"/>
            <el-table-column label="证据" width="160"><template #default="{row}"><el-button text @click="showCandidate(row)">查看 / 修正</el-button></template></el-table-column>
          </el-table>
          <div class="pager"><el-button :disabled="candidateOffset === 0" @click="candidateOffset -= 50; loadCandidates()">上一页</el-button><el-button :disabled="!candidatePage.hasMore" @click="candidateOffset += 50; loadCandidates()">下一页</el-button></div>
        </template>
        </div></el-scrollbar>
      </el-tab-pane>
      <el-tab-pane label="执行与设置" name="settings">
        <el-scrollbar class="upgrade-scrollbar" always><div class="tab-scroll-content settings-content">
        <el-collapse>
          <el-collapse-item title="Emby、路径与媒体校验设置" name="emby">
            <div v-if="settings" class="settings-form">
              <SettingsItem label="Emby 服务地址"><el-input v-model="settings.embyUrl" placeholder="http://127.0.0.1:8096"/></SettingsItem>
              <SettingsItem label="Emby API Key"><el-input v-model="settings.embyApiKey" type="password" show-password :placeholder="settings.embyKeyConfigured ? '已配置，留空保留' : '未配置'"/></SettingsItem>
              <SettingsItem label="清除 Emby Key"><el-switch v-model="settings.clearEmbyKey"/></SettingsItem>
              <SettingsItem label="媒体库 ID"><el-input v-model="libraryText" placeholder="多个 ID 以逗号分隔；连接自检可查看可用库"/></SettingsItem>
              <SettingsItem label="Emby → 本地"><el-input v-model="embyMappingText" type="textarea" :rows="3" placeholder="/media/anime => D:\Media\Anime"/></SettingsItem>
              <SettingsItem label="下载器 → 本地"><el-input v-model="downloaderMappingText" type="textarea" :rows="3" placeholder="/downloads => D:\Downloads"/></SettingsItem>
              <SettingsItem label="TV / WEB 根目录"><el-input v-model="settings.tvRoot"/></SettingsItem>
              <SettingsItem label="电影根目录"><el-input v-model="settings.movieRoot"/></SettingsItem>
              <SettingsItem label="独立下载暂存根目录"><el-input v-model="settings.stagingRoot" placeholder="必须位于 Emby 扫描范围外"/></SettingsItem>
              <SettingsItem label="ffprobe 路径"><el-input v-model="settings.ffprobe"/></SettingsItem>
              <SettingsItem label="ffmpeg 路径"><el-input v-model="settings.ffmpeg"/></SettingsItem>
              <SettingsItem label="全局默认规则"><el-select v-model="settings.defaultPolicyId" clearable><el-option v-for="p in policyRows" :key="p.id" :value="p.id" :label="p.name"/></el-select></SettingsItem>
              <SettingsItem label="洗版完成消息"><el-switch v-model="settings.notifications"/><span>复用已启用的消息通知渠道，文件动作不执行</span></SettingsItem>
              <SettingsItem><el-button :loading="busy" type="primary" @click="saveSettings">保存设置</el-button><el-button @click="inspect('/emby/test', 'Emby 连接与媒体库')">连接自检</el-button><el-button @click="syncLibraries">索引所选媒体库</el-button><el-button @click="importEmby">从现有通知导入连接</el-button></SettingsItem>
            </div>
          </el-collapse-item>
          <el-collapse-item title="DeepSeek 辅助与预算" name="ai">
            <div v-if="settings" class="settings-form">
              <SettingsItem label="API 地址"><el-input v-model="settings.aiBaseUrl"/></SettingsItem>
              <SettingsItem label="API Key"><el-input v-model="settings.aiApiKey" type="password" show-password :placeholder="settings.aiKeyConfigured ? '已配置，留空保留' : '未配置'"/></SettingsItem>
              <SettingsItem label="清除 AI Key"><el-switch v-model="settings.clearAiKey"/></SettingsItem>
              <SettingsItem label="模型 ID"><el-select v-model="settings.aiModel" filterable allow-create default-first-option><el-option v-for="model in modelRows" :key="model.id" :value="model.id" :label="model.id"/></el-select><el-button @click="loadModels">获取模型</el-button></SettingsItem>
              <SettingsItem label="启用辅助"><el-switch v-model="settings.aiEnabled"/><span class="hint">先保存地址和模型，再执行结构化输出自检</span></SettingsItem>
              <SettingsItem label="每日请求上限"><el-input-number v-model="settings.aiDailyRequests" :min="0"/></SettingsItem>
              <SettingsItem label="每日 token 上限"><el-input-number v-model="settings.aiDailyTokens" :min="0" :step="100000"/></SettingsItem>
              <SettingsItem label="AI 并发"><el-input-number v-model="settings.aiConcurrency" :min="1" :max="8"/></SettingsItem>
              <SettingsItem label="新自动下载并发"><el-input-number v-model="settings.downloadConcurrency" :min="1" :max="20"/></SettingsItem>
              <SettingsItem label="预留空间（字节）"><el-input-number v-model="settings.reserveBytes" :min="0" :step="1073741824"/></SettingsItem>
              <SettingsItem label="无进展小时上限"><el-input-number v-model="settings.noProgressHours" :min="1"/></SettingsItem>
              <SettingsItem label="总等待小时上限"><el-input-number v-model="settings.downloadTimeoutHours" :min="1"/></SettingsItem>
              <SettingsItem><el-button type="primary" @click="saveSettings">保存设置</el-button><el-button @click="inspect('/ai/test', 'AI 结构化输出自检')">连接自检</el-button></SettingsItem>
            </div>
          </el-collapse-item>
        </el-collapse>
        <h3>执行记录</h3>
        <el-table :data="jobPage.items || []" empty-text="尚无执行记录">
          <el-table-column prop="kind" label="任务类型" width="110"/>
          <el-table-column label="当前阶段" min-width="180"><template #default="{row}">{{ label(row.state) }}</template></el-table-column>
          <el-table-column prop="message" label="原因与进度" min-width="260"/>
          <el-table-column label="操作" min-width="220"><template #default="{row}">
            <el-button text @click="showJob(row)">逐文件记录</el-button>
            <el-button v-if="row.retryable" text @click="action(`/jobs/${row.id}/retry`)">重试</el-button>
            <el-button v-if="['QUEUED','DOWNLOADING','PAUSED','RETRY_WAIT'].includes(row.state)" text @click="action(`/jobs/${row.id}/cancel`)">取消</el-button>
          </template></el-table-column>
        </el-table>
        <div class="pager"><el-button :disabled="jobOffset === 0" @click="jobOffset -= 50; loadJobs()">上一页</el-button><el-button :disabled="!jobPage.hasMore" @click="jobOffset += 50; loadJobs()">下一页</el-button></div>
        </div></el-scrollbar>
      </el-tab-pane>
    </el-tabs>
    </div>
    <el-dialog v-model="campaignDialog" :title="campaignForm.id ? '编辑订阅' : '新增季度订阅'" width="min(760px, 94vw)" top="6vh">
      <div class="campaign-body">
      <SettingsItem label="名称"><el-input v-model="campaignForm.name" aria-label="订阅名称" placeholder="例如：2024 年春季动画"/></SettingsItem>
      <SettingsItem v-if="legacyCampaign" label="订阅范围"><el-radio-group v-model="campaignForm.catalogSource" @change="loadMikanSeasons"><el-radio value="DATE">保留原日期</el-radio><el-radio value="MIKAN">改为 Mikan 季度</el-radio></el-radio-group></SettingsItem>
      <template v-if="campaignForm.catalogSource === 'MIKAN'">
        <SettingsItem label="年份与季度"><div class="season-controls">
          <el-select v-model="campaignForm.mikanYear" aria-label="订阅年份" placeholder="选择年份" filterable :loading="seasonLoading" @change="changeMikanYear"><el-option v-for="year in mikanYears" :key="year" :label="`${year} 年`" :value="year"/></el-select>
          <el-select v-model="campaignForm.mikanSeason" aria-label="订阅季度" placeholder="选择季度" :loading="seasonLoading" @change="loadMikanPreview"><el-option v-for="s in mikanQuarters" :key="s.season" :label="seasonLabels[s.season]" :value="s.season"/></el-select>
          <el-button :loading="seasonLoading" @click="loadMikanSeasons">刷新目录</el-button>
        </div></SettingsItem>
        <el-alert v-if="seasonError" :title="seasonError" type="error" :closable="false" show-icon/>
        <SettingsItem label="首播范围"><span>{{ quarterRange || '请先选择年份和季度' }}</span></SettingsItem>
        <section class="season-preview" v-loading="previewLoading">
          <div class="settings-section-heading"><h3>季度目录预览</h3><span class="hint">目录 {{ seasonEntries.length }} 部 · 已选 {{ selectedSeasonCount }} 部 · 已取消 {{ seasonEntries.length - selectedSeasonCount }} 部</span></div>
          <p class="hint">目录来自 Mikan；保存后核对 Bangumi 身份与首播日期，补充无资源动画。持续播出的往年作品按首播范围排除。取消勾选可排除此订阅中的指定作品，保存后生效；后续新发现作品默认订阅。</p>
          <p v-if="resolvingTitles.size" class="hint">正在核对取消作品的身份，请稍候；核对完成后可保存。</p>
          <el-alert v-if="previewError" :title="previewError" type="error" :closable="false"/>
          <el-table v-else :data="seasonEntries" row-key="mikanId" max-height="300" empty-text="选择年份和季度后读取 Mikan 目录">
            <el-table-column label="订阅" width="70"><template #default="{row}"><el-checkbox :model-value="!campaignForm.excludedMikanSubjects?.[row.mikanId] && !resolvingTitles.has(row.mikanId)" :aria-label="`订阅${row.title}`" :disabled="busy || resolvingTitles.has(row.mikanId)" @change="value => setCampaignTitle(row, value)"/></template></el-table-column>
            <el-table-column prop="title" label="作品" min-width="200"/><el-table-column prop="category" label="更新分组" width="105"/>
            <el-table-column label="来源" width="85"><template #default="{row}"><a :href="row.url" target="_blank" rel="noopener noreferrer" class="source-link">Mikan ↗</a></template></el-table-column>
          </el-table>
        </section>
      </template>
      <template v-else>
        <SettingsItem label="开始日期"><el-date-picker v-model="campaignForm.startDate" aria-label="开始日期" value-format="YYYY-MM-DD"/></SettingsItem>
        <SettingsItem label="结束日期"><el-date-picker v-model="campaignForm.endDate" aria-label="结束日期" value-format="YYYY-MM-DD"/></SettingsItem>
      </template>
      <SettingsItem label="质量规则"><el-select v-model="campaignForm.policyId" clearable placeholder="使用全局默认规则"><el-option v-for="p in policyRows" :key="p.id" :label="p.name" :value="p.id"/></el-select></SettingsItem>
      <SettingsItem label="输出媒体库"><el-select v-model="campaignForm.libraryId" aria-label="输出媒体库"><el-option value="UNASSIGNED" label="仅订阅监测（暂不关联媒体库）"/><el-option v-for="id in settings?.libraryIds || []" :key="id" :value="id" :label="id"/></el-select></SettingsItem>
      <p v-if="campaignForm.libraryId === 'UNASSIGNED'" class="hint">核对季度作品、资源与规则；自动下载和媒体替换会等待关联媒体库。</p>
      <SettingsItem label="任务优先级"><el-input-number v-model="campaignForm.priority"/></SettingsItem>
      <SettingsItem label="启用"><el-switch v-model="campaignForm.enabled"/><span class="hint">暂停保留任务与已发现作品</span></SettingsItem>
      <p class="hint">规则留空时使用已保存的全局默认规则；作品覆盖优先于任务规则。资源发布日期不受首播区间限制。</p>
      </div>
      <template #footer><el-button :disabled="busy" @click="campaignDialog = false">取消</el-button><el-button :loading="busy" type="primary" :disabled="!canSaveCampaign" @click="saveCampaign">保存订阅</el-button></template>
    </el-dialog>
    <el-dialog v-model="policyDialog" :title="policyForm.id ? '编辑质量规则' : '新增质量规则'" width="min(820px, 94vw)" top="6vh">
      <div class="policy-body">
      <el-form label-width="130px">
        <el-form-item label="规则预设"><div class="preset-controls"><el-select v-model="selectedPreset" filterable aria-label="规则预设" placeholder="选择预设，再应用到当前草稿"><el-option v-for="p in presetCatalog.presets || []" :key="p.id" :value="p.id" :label="p.name"/></el-select><el-button :disabled="!selectedPreset" @click="usePreset">应用预设</el-button></div><p class="hint">{{ selectedPresetInfo?.description || '预设可继续编辑，保存后才生效。' }}</p></el-form-item>
        <el-form-item label="参数标签预设"><div class="preset-controls"><el-select v-model="selectedParameter" filterable aria-label="参数标签预设" placeholder="选择单项参数"><el-option v-for="p in parameterPresets" :key="p.id" :value="p.id" :label="p.name"/></el-select><el-button :disabled="!selectedParameter" @click="useParameterPreset">应用参数</el-button></div><p class="hint">{{ selectedParameterInfo?.description || '只替换所列参数；缺少的参与维度追加到末尾，可继续调整优先级。' }}</p></el-form-item>
        <el-form-item label="名称"><el-input v-model="policyForm.name"/></el-form-item>
        <el-form-item label="维度优先级">
          <div class="dimension-list"><div v-for="(d, index) in policyForm.dimensions" :key="d" draggable="true" @dragstart="dragIndex = index" @dragover.prevent @drop.prevent="policyForm.dimensions = moveDimension(policyForm.dimensions, dragIndex, index)">
            <span>{{ index + 1 }}. {{ dimensionLabels[d] }}</span><el-button size="small" :disabled="index === 0" :aria-label="`提高${dimensionLabels[d]}优先级`" @click="policyForm.dimensions = moveDimension(policyForm.dimensions, index, index - 1)">↑</el-button><el-button size="small" :disabled="index === policyForm.dimensions.length - 1" :aria-label="`降低${dimensionLabels[d]}优先级`" @click="policyForm.dimensions = moveDimension(policyForm.dimensions, index, index + 1)">↓</el-button>
          </div></div>
        </el-form-item>
        <el-form-item label="参与维度"><el-checkbox-group v-model="policyForm.dimensions"><el-checkbox v-for="(name, key) in dimensionLabels" :key="key" :value="key">{{ name }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item v-for="field in ['subtitles','groups','resolutions','sources','packaging','codecs']" :key="field" :label="fieldLabels[field]">
          <div class="ranking-editor">
            <el-select v-model="policyForm[field]" multiple filterable allow-create default-first-option :filter-method="isFansubField(field) ? query => fansubSearch[field] = query : undefined" @visible-change="visible => { if(!visible) fansubSearch[field] = '' }" :aria-label="fieldLabels[field]"><el-option v-for="value in rankOptions(field)" :key="value" :value="value" :label="parameterLabel(field, value)"><span>{{ optionLabel(field, value) }}</span></el-option></el-select>
            <div v-if="field === 'groups'" class="fansub-preset"><el-button size="small" :disabled="!presetCatalog.groups?.length" @click="useFansubPreference(true)">应用全部 {{ presetCatalog.groups?.length || 0 }} 组</el-button><el-button size="small" :disabled="!presetCatalog.groups?.length" @click="useFansubPreference(false)">原六组优先</el-button><span class="hint">目录 {{ presetCatalog.groups?.length || 0 }} 组 · 当前排序 {{ policyForm.groups.length }} 组。原六组优先，其余组按后备顺序加入；可搜索别名，顺序可继续调整。</span></div>
            <div class="ranking-list" :aria-label="`${fieldLabels[field]}优先级`">
              <div v-for="(value, index) in policyForm[field]" :key="value" draggable="true" @dragstart="rankDrag = {field, index}" @dragover.prevent @drop.prevent="reorderRank(field, rankDrag.field === field ? rankDrag.index : -1, index)">
                <span>{{ index + 1 }}. {{ parameterLabel(field, value) }}</span>
                <el-button size="small" :disabled="index === 0" :aria-label="`提高${fieldLabels[field]}${value}优先级`" @click="reorderRank(field, index, index - 1)">↑</el-button>
                <el-button size="small" :disabled="index === policyForm[field].length - 1" :aria-label="`降低${fieldLabels[field]}${value}优先级`" @click="reorderRank(field, index, index + 1)">↓</el-button>
              </div>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="最低分辨率"><el-input-number v-model="policyForm.minimumResolution" :min="0" :max="16384"/></el-form-item>
        <el-form-item label="必须中文字幕"><el-switch v-model="policyForm.requireChinese"/></el-form-item>
        <el-form-item label="必须字幕语言"><el-select v-model="policyForm.requiredLanguages" multiple><el-option v-for="v in ['CHS','CHT','JPN']" :key="v" :value="v" :label="parameterLabel('requiredLanguages', v)"/></el-select></el-form-item>
        <el-form-item label="必须双语同屏"><el-switch v-model="policyForm.requireBilingual"/></el-form-item>
        <el-form-item v-for="field in ['allowedGroups','blockedGroups','containers']" :key="field" :label="{allowedGroups:'允许字幕组',blockedGroups:'禁止字幕组',containers:'允许容器'}[field]"><el-select v-model="policyForm[field]" multiple filterable allow-create :aria-label="{allowedGroups:'允许字幕组',blockedGroups:'禁止字幕组',containers:'允许容器'}[field]" :filter-method="isFansubField(field) ? query => fansubSearch[field] = query : undefined" @visible-change="visible => { if(!visible) fansubSearch[field] = '' }"><el-option v-for="value in rankOptions(field)" :key="value" :value="value" :label="parameterLabel(field, value)"><span>{{ optionLabel(field, value) }}</span></el-option></el-select></el-form-item>
        <el-form-item label="字幕组别名 JSON"><el-input v-model="aliasText" type="textarea" placeholder='{"喵萌奶茶屋":"喵萌奶茶屋"}'/></el-form-item>
      </el-form>
      <el-collapse><el-collapse-item title="字幕组预设来源与别名" name="fansub-sources"><p class="hint">{{ presetCatalog.note }} 资料核对：{{ presetCatalog.updatedAt }}</p><p v-for="group in presetCatalog.groups || []" :key="group.name"><a :href="group.source" target="_blank" rel="noopener noreferrer" class="source-link">{{ group.name }} ↗</a><span class="hint"> · {{ group.aliases.join('、') || '英文名称忽略大小写' }}</span></p></el-collapse-item></el-collapse>
      <p>排序从高到低，可拖动或使用上下按钮调整。高维改善可接受低维下降，硬条件始终生效。空字幕组列表表示不比较字幕组；联合组按最高排名参与组比较。</p>
      <el-button @click="previewPolicy">预览当前草稿：字幕组改善、分辨率下降</el-button>
      <p v-if="preview">{{ preview.explanation?.join('；') }}</p>
      </div>
      <template #footer><el-button :disabled="busy" @click="policyDialog = false">取消</el-button><el-button :loading="busy" type="primary" @click="savePolicy">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="targetDialog" title="作品映射与排除" width="min(650px, 94vw)">
      <el-form-item label="关联媒体库"><el-select v-model="targetForm.libraryId" :disabled="targetForm.libraryLocked"><el-option v-for="id in settings?.libraryIds || []" :key="id" :value="id" :label="id"/></el-select></el-form-item>
      <el-form label-width="130px">
        <el-form-item label="排除此作品"><el-switch v-model="targetForm.excluded"/></el-form-item>
        <el-form-item label="覆盖质量规则"><el-select v-model="targetForm.policyId" clearable><el-option v-for="p in policyRows" :key="p.id" :value="p.id" :label="p.name"/></el-select></el-form-item>
        <el-form-item label="Emby Series ID"><el-input v-model="targetForm.embySeriesId"/></el-form-item>
        <el-form-item label="季编号"><el-input-number v-model="targetForm.season" :min="0" :max="1000"/></el-form-item>
        <el-form-item label="章节映射 JSON"><el-input v-model="episodeMapText" type="textarea" placeholder='{"2:1":"13"}'/></el-form-item>
        <el-form-item label="补充 RSS"><el-input v-model="rssText" type="textarea" placeholder="每行一个 Mikan 或自定义 RSS 地址"/></el-form-item>
      </el-form>
      <template #footer><el-button type="primary" @click="saveTarget">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="detailDialog" :title="detailTitle" width="min(1000px, 96vw)">
      <pre class="detail">{{ JSON.stringify(detail, null, 2) }}</pre>
      <template v-if="detailTitle === '候选证据'"><el-button @click="correctionText = JSON.stringify(detail, null, 2)">编辑结构化修正</el-button><el-input v-if="correctionText" v-model="correctionText" type="textarea" :rows="10"/><el-button v-if="correctionText" @click="resolveCandidate">提交修正并重新验证</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup>
import {ref, computed, watch, onMounted, onActivated, onDeactivated, onBeforeUnmount} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import api from '@/js/api.js'
import PageHeaderView from '@/view/custom/PageHeaderView.vue'
import SettingsItem from '@/view/custom/SettingsItem.vue'
import {dimensionLabels, jobLabels, moveDimension, parseMappings, mappingText, defaultPolicy, seasonLabels, quarterDateRange, mikanSeasonChoices, applyPolicyPreset, applyFansubPreference, parameterOptions, parameterLabel, parameterPresets, applyParameterPreset, mergeFansubAliases} from '@/js/upgrade.js'
const base = '/api/v2/upgrade'
const status = ref({}), loading = ref(false), busy = ref(false), tab = ref('overview')
const config = ref({}), settings = ref(null), policyRows = ref([]), campaignRows = ref([]), modelRows = ref([])
const targetPage = ref({}), candidatePage = ref({}), jobPage = ref({})
const versionPage = ref({}), aliasText = ref('{}')
const targetOffset = ref(0), candidateOffset = ref(0), jobOffset = ref(0), versionOffset = ref(0), selectedTarget = ref(null)
const targetCoverage = ref(null)
const embyMappingText = ref(''), downloaderMappingText = ref(''), libraryText = ref('')
const campaignDialog = ref(false), campaignForm = ref({}), policyDialog = ref(false), policyForm = ref(defaultPolicy()), preview = ref(null), dragIndex = ref(-1)
const fansubSearch = ref({})
const rankDrag = ref({field: '', index: -1})
const presetCatalog = ref({groups: [], presets: []}), selectedPreset = ref('')
const selectedParameter = ref('')
const selectedParameterInfo = computed(() => parameterPresets.find(p => p.id === selectedParameter.value))
const selectedPresetInfo = computed(() => presetCatalog.value.presets.find(p => p.id === selectedPreset.value))
const legacyCampaign = ref(false), mikanSeasons = ref([]), seasonEntries = ref([])
const seasonLoading = ref(false), previewLoading = ref(false), seasonError = ref(''), previewError = ref('')
const tabDescriptions = {overview: '运行模式、任务覆盖与扫描进度', campaigns: '按 Mikan 年份与季度订阅动画，持续跟踪优质资源', policies: '设置质量维度的比较顺序与必须满足的条件', targets: '管理作品档案、候选资源与章节映射', settings: '配置媒体库、路径与辅助分析，查看执行记录'}
const mikanYears = computed(() => [...new Set(mikanSeasons.value.map(s => s.year))].sort((a, b) => b - a))
const mikanQuarters = computed(() => mikanSeasonChoices(mikanSeasons.value, campaignForm.value.mikanYear))
const quarterRange = computed(() => { const range = quarterDateRange(campaignForm.value.mikanYear, campaignForm.value.mikanSeason); return range ? `${range.startDate} 至 ${range.endDate}` : '' })
const canSaveCampaign = computed(() => campaignForm.value.name?.trim() && !resolvingTitles.value.size && campaignForm.value.libraryId && (campaignForm.value.catalogSource !== 'MIKAN' || (!seasonLoading.value && !previewLoading.value && !seasonError.value && mikanQuarters.value.some(s => s.season === campaignForm.value.mikanSeason))))
const resolvingTitles = ref(new Set())
const selectedSeasonCount = computed(() => seasonEntries.value.filter(row => !campaignForm.value.excludedMikanSubjects?.[row.mikanId]).length)
let campaignSelectionGeneration = 0, campaignSelectionKey = ''
watch(campaignDialog, open => { if(!open) ++campaignSelectionGeneration })
let seasonRequest = 0, previewRequest = 0, suggestedCampaignName = ''
const targetDialog = ref(false), targetForm = ref({}), episodeMapText = ref('{}'), rssText = ref('')
const detailDialog = ref(false), detailTitle = ref(''), detail = ref({}), correctionText = ref('')
const fieldLabels = {subtitles: '字幕组合排序', groups: '字幕组排序', resolutions: '分辨率排序', sources: '来源排序', packaging: '封装排序', codecs: '编码排序'}
const label = value => jobLabels[value] || value
const clone = value => JSON.parse(JSON.stringify(value))
const coverageRows = computed(() => {
  const metrics = status.value.coverageMetrics || {}, total = metrics.totalTargets || 0
  return Object.entries({discoveredResources:'已发现资源', installedAndCleaned:'已安装并清理旧版', embyCollected:'新版已被 Emby 收录', needsReview:'需要人工核对', unavailableResources:'含暂不可用资源', serviceFailures:'服务失败等待重试', aiBudgetBlocked:'AI 预算阻塞'}).map(([key, label]) => ({label, count:metrics[key] || 0, rate:total ? `${((metrics[key] || 0) * 100 / total).toFixed(1)}%` : '—'}))
})
watch(policyDialog, value => { if(value) { aliasText.value = JSON.stringify(policyForm.value.groupAliases || {}, null, 2); preview.value = null } })
let timer
async function loadStatus() { status.value = (await api.get(`${base}/status`)).data }
async function loadTargets() { targetPage.value = (await api.get(`${base}/targets?offset=${targetOffset.value}`)).data }
async function loadJobs() { jobPage.value = (await api.get(`${base}/jobs?offset=${jobOffset.value}`)).data }
async function loadCandidates() { candidatePage.value = (await api.get(`${base}/candidates?targetId=${encodeURIComponent(selectedTarget.value.id)}&offset=${candidateOffset.value}`)).data }
async function loadSettings() {
  config.value = (await api.get('/api/v2/config')).data
  settings.value = clone(config.value.upgrade)
  embyMappingText.value = mappingText(settings.value.embyMappings)
  downloaderMappingText.value = mappingText(settings.value.downloaderMappings)
  libraryText.value = (settings.value.libraryIds || []).join(', ')
}
async function reload() {
  loading.value = true
  try { await Promise.all([loadStatus(), loadTargets(), loadJobs(), loadSettings(),
    api.get(`${base}/policies`).then(r => policyRows.value = r.data), api.get(`${base}/campaigns`).then(r => campaignRows.value = r.data),
    api.get(`${base}/policy-presets`).then(r => presetCatalog.value = r.data)]) }
  finally { loading.value = false }
}
async function setMode(mode) {
  busy.value = true
  try { const previous = status.value.mode?.mode; status.value.mode = (await api.put(`${base}/mode`, {mode, expectedRevision: status.value.mode.revision})).data
    if (mode === 'AUTOMATIC' && previous === 'LEGACY') ElMessage.success('已关闭原版洗版')
    if (mode === 'LEGACY' && previous === 'AUTOMATIC') ElMessage.success('已关闭自动化洗版')
    await loadSettings()
  } catch(e) { if(e.status === 409) { await loadStatus(); await loadSettings() } else throw e } finally { busy.value = false }
}
async function action(path) { await api.post(base + path, {}); await Promise.all([loadStatus(), loadJobs()]) }
function newCampaign() { ++campaignSelectionGeneration; resolvingTitles.value = new Set(); campaignSelectionKey = ''; suggestedCampaignName = ''; legacyCampaign.value = false; campaignForm.value = {name: '', catalogSource: 'MIKAN', mikanYear: null, mikanSeason: null, policyId: null, libraryId: settings.value?.libraryIds?.[0] || 'UNASSIGNED', priority: 0, enabled: true, excludedMikanSubjects: {}}; campaignDialog.value = true; loadMikanSeasons() }
function editCampaign(row) { ++campaignSelectionGeneration; resolvingTitles.value = new Set(); campaignSelectionKey = `${row.mikanYear}:${row.mikanSeason}`; suggestedCampaignName = ''; legacyCampaign.value = row.catalogSource !== 'MIKAN'; campaignForm.value = {...clone(row), excludedMikanSubjects: clone(row.excludedMikanSubjects || {}), catalogSource: row.catalogSource || 'DATE'}; campaignDialog.value = true; loadMikanSeasons() }
async function loadMikanSeasons() {
  if(campaignForm.value.catalogSource !== 'MIKAN') { ++seasonRequest; ++previewRequest; seasonLoading.value = false; previewLoading.value = false; return }
  const request = ++seasonRequest; ++previewRequest; seasonLoading.value = true; previewLoading.value = false; seasonError.value = ''; previewError.value = ''; seasonEntries.value = []
  try {
    const rows = (await api.get(`${base}/mikan/seasons`, {silent: true})).data
    if(request !== seasonRequest || !campaignDialog.value) return
    mikanSeasons.value = rows
    if(!campaignForm.value.mikanYear) {
      const selected = rows.find(s => s.select) || rows[0]
      campaignForm.value.mikanYear = selected?.year; campaignForm.value.mikanSeason = selected?.season
    }
    if(!mikanQuarters.value.some(s => s.season === campaignForm.value.mikanSeason)) seasonError.value = 'Mikan 未提供当前选定季度，请重新选择。'
    else await loadMikanPreview()
  } catch(e) { if(request === seasonRequest) seasonError.value = `Mikan 目录暂不可用：${e.message}。可检查设置中的 Mikan 地址或代理后重试。` }
  finally { if(request === seasonRequest) seasonLoading.value = false }
}
function changeMikanYear() {
  seasonError.value = ''
  if(!mikanQuarters.value.some(s => s.season === campaignForm.value.mikanSeason)) campaignForm.value.mikanSeason = mikanQuarters.value[0]?.season
  loadMikanPreview()
}
async function loadMikanPreview() {
  const request = ++previewRequest, {mikanYear: year, mikanSeason: season} = campaignForm.value
  const key = `${year}:${season}`
  if(campaignSelectionKey && campaignSelectionKey !== key) { ++campaignSelectionGeneration; campaignForm.value.excludedMikanSubjects = {}; resolvingTitles.value = new Set() }
  campaignSelectionKey = key
  seasonEntries.value = []; previewError.value = ''; seasonError.value = ''; previewLoading.value = false
  if(!quarterDateRange(year, season)) return
  if(!campaignForm.value.name.trim() || campaignForm.value.name === suggestedCampaignName) { suggestedCampaignName = `${year} 年${season}季动画`; campaignForm.value.name = suggestedCampaignName }
  previewLoading.value = true
  try { const rows = (await api.get(`${base}/mikan/preview?year=${year}&season=${encodeURIComponent(season)}`, {silent: true})).data; if(request === previewRequest && campaignDialog.value) seasonEntries.value = rows }
  catch(e) { if(request === previewRequest) previewError.value = `季度目录读取失败：${e.message}。可点击刷新目录重试。` }
  finally { if(request === previewRequest) previewLoading.value = false }
}
async function setCampaignTitle(row, selected) {
  const generation = campaignSelectionGeneration
  if(selected) { delete campaignForm.value.excludedMikanSubjects[row.mikanId]; return }
  resolvingTitles.value.add(row.mikanId)
  try {
    const {data} = await api.get(`${base}/mikan/exclusion-subject?mikanId=${encodeURIComponent(row.mikanId)}`, {silent: true})
    if(generation === campaignSelectionGeneration && campaignDialog.value) campaignForm.value.excludedMikanSubjects[row.mikanId] = data.subjectId
  } catch(e) { if(generation === campaignSelectionGeneration && campaignDialog.value) ElMessage.error(`无法取消“${row.title}”：${e.message}。作品仍保持订阅，请重试。`) }
  finally { if(generation === campaignSelectionGeneration) resolvingTitles.value.delete(row.mikanId) }
}
async function saveCampaign() { if(resolvingTitles.value.size) return; busy.value = true; try { const c = campaignForm.value; await api[c.id ? 'put' : 'post'](`${base}/campaigns${c.id ? '/' + c.id : ''}`, c); campaignDialog.value = false; campaignRows.value = (await api.get(`${base}/campaigns`)).data } finally { busy.value = false } }
async function toggleCampaign(row) { await api.put(`${base}/campaigns/${row.id}`, {...row, enabled: !row.enabled}); campaignRows.value = (await api.get(`${base}/campaigns`)).data }
function editPolicy(row) { policyForm.value = row ? clone(row) : defaultPolicy(); selectedPreset.value = ''; selectedParameter.value = ''; fansubSearch.value = {}; policyDialog.value = true }
const isFansubField = field => field === 'groups' || field.endsWith('Groups')
function rankOptions(field) {
  const values = [...new Set([...(isFansubField(field) ? presetCatalog.value.groups.map(g => g.name) : []), ...(parameterOptions[field] || []), ...policyForm.value[field]])]
  const query = (fansubSearch.value[field] || '').normalize('NFKC').toLowerCase().trim()
  return isFansubField(field) && query ? values.filter(value => optionLabel(field, value).normalize('NFKC').toLowerCase().includes(query)) : values
}
function optionLabel(field, value) {
  const group = presetCatalog.value.groups.find(g => g.name === value)
  return group ? [group.name, ...group.aliases].join(' · ') : parameterLabel(field, value)
}
function useParameterPreset() { policyForm.value = applyParameterPreset(policyForm.value, selectedParameterInfo.value); preview.value = null }
function usePreset() { policyForm.value = applyPolicyPreset(policyForm.value, selectedPresetInfo.value); aliasText.value = JSON.stringify(policyForm.value.groupAliases || {}, null, 2); preview.value = null }
function useFansubPreference(all) {
  try {
    policyForm.value.groupAliases = JSON.parse(aliasText.value)
    policyForm.value = applyFansubPreference(policyForm.value, all ? presetCatalog.value.groups : presetCatalog.value.groups.slice(0, 6))
    aliasText.value = JSON.stringify(policyForm.value.groupAliases, null, 2); preview.value = null
  } catch(e) { if(e instanceof SyntaxError) ElMessage.error('字幕组别名 JSON 格式错误'); else throw e }
}
function reorderRank(field, from, to) { policyForm.value[field] = moveDimension(policyForm.value[field], from, to) }
async function savePolicy() { busy.value = true; try { const p = {...policyForm.value, groupAliases: mergeFansubAliases(JSON.parse(aliasText.value), presetCatalog.value.groups), resolutions: policyForm.value.resolutions.map(Number)}; await api[p.id ? 'put' : 'post'](`${base}/policies${p.id ? '/' + p.id : ''}`, p); policyDialog.value = false; policyRows.value = (await api.get(`${base}/policies`)).data; ElMessage.success('规则已保存') } catch(e) { if(e instanceof SyntaxError) ElMessage.error('字幕组别名 JSON 格式错误'); else if(e.status === 409) policyRows.value = (await api.get(`${base}/policies`)).data; else throw e } finally { busy.value = false } }
async function deletePolicy(row) {
  try { await ElMessageBox.confirm(`删除规则“${row.name}”？已有媒体文件不会改变；正在被引用的规则需先解除引用。`, '删除质量规则', {confirmButtonText: '删除规则', cancelButtonText: '取消', type: 'warning'}) }
  catch(reason) { if(reason === 'cancel' || reason === 'close') return; throw reason }
  busy.value = true
  try { await api.del(`${base}/policies/${encodeURIComponent(row.id)}?expectedRevision=${row.revision}`); policyRows.value = (await api.get(`${base}/policies`)).data; ElMessage.success('规则已删除') }
  catch(e) { if(e.status === 409) policyRows.value = (await api.get(`${base}/policies`)).data; else throw e }
  finally { busy.value = false }
}
async function previewPolicy() {
  const common = {subjectId: 'example', episodes: ['1'], languages: ['CHS'], container: 'mkv', chapterType: 'NORMAL', correction: 1}
  preview.value = (await api.post(`${base}/evaluate`, {policy: {...policyForm.value, groupAliases: mergeFansubAliases(JSON.parse(aliasText.value), presetCatalog.value.groups)}, candidate: {...common, groups: [policyForm.value.groups[0] || 'A组'], resolution: 1080}, current: {...common, groups: [policyForm.value.groups[1] || 'B组'], resolution: 2160}})).data
}
async function showCandidates(target) { selectedTarget.value = target; targetCoverage.value = null; candidateOffset.value = 0; versionOffset.value = 0; await Promise.all([loadCandidates(), loadVersions(), api.get(`${base}/targets/${encodeURIComponent(target.id)}/coverage`).then(response => { targetCoverage.value = response.data })]) }
async function loadVersions() { versionPage.value = (await api.get(`${base}/versions?targetId=${encodeURIComponent(selectedTarget.value.id)}&offset=${versionOffset.value}`)).data }
function showCandidate(row) {
  detailTitle.value = '候选证据'; detail.value = row; detailDialog.value = true
  correctionText.value = JSON.stringify(Object.fromEntries(['subjectId','episodes','season','groups','languages','subtitleLayout','subtitlePackaging','resolution','chapterType','contentEdition'].map(field => [field, row[field]])), null, 2)
}
async function exportPreview(targetId) {
  busy.value = true
  try {
    const report = {generatedAt: new Date().toISOString(), readOnly: true, decisions: [], reasonDistribution: {}}
    for(let offset = 0;; offset += 50) {
      const page = (await api.get(`${base}/preview?offset=${offset}${targetId ? '&targetId=' + encodeURIComponent(targetId) : ''}`)).data
      report.decisions.push(...page.decisions)
      for(const [reason, count] of Object.entries(page.reasonDistribution)) report.reasonDistribution[reason] = (report.reasonDistribution[reason] || 0) + count
      if(!page.hasMore) break
    }
    const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], {type: 'application/json'}))
    const link = document.createElement('a'); link.href = url; link.download = `upgrade-preview-${Date.now()}.json`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success('已导出只读比较结果，未创建下载或文件操作')
  } finally { busy.value = false }
}
async function protect(row, value) { await api.put(`${base}/versions/${row.id}/protection`, {protectedVersion: value}); await loadVersions() }
async function importEmby() { await api.post(`${base}/emby/import-notification`, {}); await loadSettings(); await loadStatus() }
function editTarget(target) { targetForm.value = {...clone(target), policyId: target.policyOverride, libraryLocked: target.libraryId !== 'UNASSIGNED'}; episodeMapText.value = JSON.stringify(target.episodeMapping || {}); rssText.value = (target.rssUrls || []).join('\n'); targetDialog.value = true }
async function saveTarget() { try { await api.put(`${base}/targets/${targetForm.value.id}/override`, {...targetForm.value, episodeMapping: JSON.parse(episodeMapText.value), rssUrls: rssText.value.split('\n').map(s => s.trim()).filter(Boolean)}); targetDialog.value = false; await loadTargets() } catch(e) { if(e instanceof SyntaxError) ElMessage.error('章节映射 JSON 格式错误'); else throw e } }
async function saveSettings() {
  busy.value = true
  try { settings.value.embyMappings = parseMappings(embyMappingText.value); settings.value.downloaderMappings = parseMappings(downloaderMappingText.value); settings.value.libraryIds = libraryText.value.split(',').map(s => s.trim()).filter(Boolean)
    config.value = (await api.put('/api/v2/config', {...config.value, upgrade: settings.value})).data
    await loadSettings(); await loadStatus(); ElMessage.success('设置已保存')
  } catch(e) { if(!e.status) ElMessage.error(e.message); else throw e } finally { busy.value = false }
}
async function inspect(path, title) { detail.value = (await api.post(base + path, {})).data; detailTitle.value = title; correctionText.value = ''; detailDialog.value = true; await loadSettings() }
async function syncLibraries() { for(const id of settings.value.libraryIds) await api.post(`${base}/emby/sync`, {libraryId: id}); await loadJobs() }
async function loadModels() { modelRows.value = (await api.get(`${base}/ai/models`)).data.data || [] }
async function showJob(job) { detail.value = (await api.get(`${base}/jobs/${job.id}`)).data; detailTitle.value = '逐文件提交与事件'; correctionText.value = ''; detailDialog.value = true }
async function resolveCandidate() { try { await api.post(`${base}/candidates/${detail.value.id}/resolve`, JSON.parse(correctionText.value)); detailDialog.value = false; correctionText.value = ''; await loadCandidates() } catch(e) { if(e instanceof SyntaxError) ElMessage.error('修正 JSON 格式错误'); else throw e } }
function stopTimer() { clearInterval(timer); timer = undefined }
onMounted(reload)
onActivated(() => { stopTimer(); timer = setInterval(() => { Promise.all([loadStatus(), loadJobs()]).catch(() => {}) }, 10000) })
onDeactivated(stopTimer)
onBeforeUnmount(stopTimer)
</script>

<style scoped>
.upgrade-page { padding-bottom: 8px; }
.upgrade-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.upgrade-tabs :deep(.el-tabs__header) { flex-shrink: 0; }
.upgrade-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; overflow: hidden; border-radius: 8px; background: var(--el-bg-color); }
.upgrade-tabs :deep(.el-tab-pane) { height: 100%; }
.upgrade-scrollbar { height: 100%; }
.tab-scroll-content { width: min(100%, 1180px); min-width: 0; margin: 0 auto; padding: 24px 28px 40px; }
.settings-content { width: min(100%, 920px); }
.settings-section + .settings-section { margin-top: 28px; padding-top: 24px; border-top: 1px solid var(--el-border-color-lighter); }
.settings-section-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 18px; }
.settings-section-heading h3 { margin: 0; font-size: 16px; font-weight: 600; line-height: 1.5; }
.settings-section-heading p { margin: 6px 0 0; }
.mode-controls { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; margin-bottom: 18px; }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
.summary-grid strong { font-size: 26px; line-height: 1.8; }
.settings-form { padding: 16px 0 0; }
.hint { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.upgrade-page :deep(.settings-item-content) { gap: 8px; }
.upgrade-page :deep(.el-alert) { margin-bottom: 18px; border-radius: 6px; }
.season-controls { display: flex; flex-wrap: wrap; gap: 8px; width: 100%; }
.season-controls :deep(.el-select) { width: 170px; flex: 1; min-width: 140px; }
.season-preview { padding: 16px; margin: 18px 0 24px; background: var(--el-fill-color-light); border-radius: 8px; }
.season-preview .settings-section-heading { margin-bottom: 8px; }
.source-link { color: var(--el-color-primary); text-decoration: none; }
.source-link:hover { text-decoration: underline; }
.campaign-body { max-height: 66vh; overflow-y: auto; padding-right: 8px; }
.pager { display: flex; justify-content: flex-end; padding: 16px 0; }
.dimension-list { width: 100%; }
.dimension-list > div { display: flex; align-items: center; gap: 8px; padding: 5px 0; }
.dimension-list span { min-width: 0; flex: 1; overflow-wrap: anywhere; }
.dimension-list :deep(.el-button + .el-button), .ranking-list :deep(.el-button + .el-button) { margin-left: 0; }
.policy-body { max-height: 62vh; overflow-y: auto; padding-right: 6px; }
.ranking-editor { width: 100%; }
.preset-controls { display: flex; gap: 8px; width: 100%; }
.preset-controls :deep(.el-select) { flex: 1; min-width: 0; }
.fansub-preset { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.ranking-list { display: flex; flex-wrap: wrap; gap: 6px 12px; margin-top: 8px; }
.ranking-list > div { display: flex; align-items: center; gap: 4px; max-width: 100%; }
.ranking-list span { min-width: 0; overflow-wrap: anywhere; }
.detail { max-height: 60vh; overflow: auto; white-space: pre-wrap; word-break: break-word; }
@media(max-width: 700px) { .tab-scroll-content { padding: 18px 4px 30px; } .mode-controls { gap: 14px; } .season-preview { padding: 12px; } .campaign-body { padding-right: 4px; } }
@media(max-width: 540px) {
  .policy-body :deep(.el-form-item) { display: block; }
  .policy-body :deep(.el-form-item__label) { width: auto !important; justify-content: flex-start; height: auto; margin-bottom: 6px; }
  .policy-body :deep(.el-form-item__content) { margin-left: 0 !important; min-width: 0; }
  .preset-controls { flex-wrap: wrap; }
  .preset-controls :deep(.el-select) { flex-basis: 180px; }
}
</style>
