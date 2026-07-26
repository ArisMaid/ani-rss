#!/bin/bash
# ANI-RSS 完全卸载脚本
# 功能: 移除服务、配置、数据和系统用户

# 定义颜色代码
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m' # 重置颜色

# 配置常量
SERVICE_NAME="ani-rss.service"
INSTALL_DIR="/opt/ani-rss"
SERVICE_USER="ani-rss"
OPERATION_ID="uninstall-$(date -u +%Y%m%dT%H%M%SZ)-$$"
INSTALL_QUARANTINE="/opt/.ani-rss-trash/${OPERATION_ID}"
SYSTEMD_QUARANTINE="/etc/systemd/system/.ani-rss-trash/${OPERATION_ID}"
BIN_QUARANTINE="/usr/local/bin/.ani-rss-trash/${OPERATION_ID}"

# 只在源路径所在文件系统内移动，确保目录隔离是原子的。
quarantine_path() {
    local source="$1"
    local quarantine_dir="$2"
    local target_name="$3"

    if [ ! -e "$source" ] && [ ! -L "$source" ]; then
        return 0
    fi

    mkdir -p -- "$quarantine_dir"
    local target="${quarantine_dir}/${target_name}"
    if [ -e "$target" ] || [ -L "$target" ]; then
        echo -e "${RED}隔离目标已存在，拒绝覆盖: ${target}${NC}"
        return 1
    fi

    local source_device
    local target_device
    source_device=$(stat -c '%d' -- "$(dirname -- "$source")")
    target_device=$(stat -c '%d' -- "$quarantine_dir")
    if [ "$source_device" != "$target_device" ]; then
        echo -e "${RED}无法同卷原子隔离，已跳过: ${source}${NC}"
        return 1
    fi

    mv -- "$source" "$target"
    echo -e "${GREEN}已隔离: ${source} -> ${target}${NC}"
}

# 检查root权限
check_root() {
    if [ "$EUID" -ne 0 ]; then
        echo -e "${RED}错误：请使用sudo或以root身份运行此脚本${NC}"
        exit 1
    fi
}

# 确认卸载
confirm_uninstall() {
    echo -e "${YELLOW}即将执行以下操作："
    echo "1. 停止并禁用服务: ${SERVICE_NAME}"
    echo "2. 隔离安装目录: ${INSTALL_DIR}"
    echo "3. 移除系统用户但保留其未声明文件: ${SERVICE_USER}"
    echo "4. 移除防火墙规则(如果存在)"
    echo -e "\n${YELLOW}文件将隔离至少 7 天，不会递归删除；完成后会输出恢复路径。${NC}"

    read -p "是否继续？(y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${GREEN}已取消卸载${NC}"
        exit 0
    fi
}

# 停止并移除服务
remove_service() {
    echo -e "${YELLOW}正在停止服务...${NC}"
    if systemctl is-active --quiet "$SERVICE_NAME"; then
        systemctl stop "$SERVICE_NAME" || {
            echo -e "${RED}服务停止失败，拒绝继续隔离文件${NC}"
            exit 1
        }
    fi

    echo -e "${YELLOW}禁用服务...${NC}"
    if systemctl is-enabled --quiet "$SERVICE_NAME"; then
        systemctl disable "$SERVICE_NAME" >/dev/null 2>&1
    fi

    echo -e "${YELLOW}隔离服务文件...${NC}"
    quarantine_path "/etc/systemd/system/${SERVICE_NAME}" \
        "$SYSTEMD_QUARANTINE" "$SERVICE_NAME" || exit 1
    quarantine_path "/etc/systemd/system/multi-user.target.wants/${SERVICE_NAME}" \
        "$SYSTEMD_QUARANTINE" "multi-user-${SERVICE_NAME}" || exit 1
    quarantine_path "/etc/systemd/system/${SERVICE_NAME}.d" \
        "$SYSTEMD_QUARANTINE" "${SERVICE_NAME}.d" || exit 1

    systemctl daemon-reload
    systemctl reset-failed
}

# 隔离安装目录
remove_install_dir() {
    echo -e "${YELLOW}隔离安装目录...${NC}"
    if [ -d "$INSTALL_DIR" ]; then
        quarantine_path "$INSTALL_DIR" "$INSTALL_QUARANTINE" "ani-rss" || {
            echo -e "${RED}目录隔离失败，请检查原路径；未执行删除${NC}"
            exit 1
        }
    else
        echo -e "${YELLOW}安装目录不存在，跳过${NC}"
    fi
    quarantine_path "/usr/local/bin/ani-rss" "$BIN_QUARANTINE" "ani-rss" || exit 1
}

# 删除系统用户
remove_service_user() {
    echo -e "${YELLOW}移除系统用户...${NC}"
    if id "$SERVICE_USER" &>/dev/null; then
        userdel "$SERVICE_USER" >/dev/null 2>&1 && \
        echo -e "${GREEN}用户已删除${NC}" || {
            echo -e "${RED}用户删除失败，请手动检查${NC}"
            exit 1
        }
    else
        echo -e "${YELLOW}用户不存在，跳过${NC}"
    fi
}

# 最终验证
verify_uninstall() {
    echo -e "\n${YELLOW}验证卸载结果：${NC}"
    local error=0

    # 检查服务状态
    if systemctl is-active --quiet "$SERVICE_NAME"; then
        echo -e "${RED}错误：服务仍在运行${NC}"
        error=1
    fi

    # 检查安装目录
    if [ -d "$INSTALL_DIR" ]; then
        echo -e "${RED}错误：安装目录仍然存在${NC}"
        error=1
    fi

    # 检查用户
    if id "$SERVICE_USER" &>/dev/null; then
        echo -e "${RED}错误：系统用户仍然存在${NC}"
        error=1
    fi

    # 综合结果
    if [ $error -eq 0 ]; then
        echo -e "${GREEN}验证通过：卸载完成${NC}"
        echo -e "${YELLOW}隔离操作 ID: ${OPERATION_ID}${NC}"
        echo "安装目录隔离区: ${INSTALL_QUARANTINE}"
        echo "systemd 隔离区: ${SYSTEMD_QUARANTINE}"
        echo "命令链接隔离区: ${BIN_QUARANTINE}"
        echo "请确认运行正常并保留至少 7 天后，再逐项人工清理上述明确路径。"
    else
        echo -e "${RED}存在未完全清理的组件，请手动处理${NC}"
        exit 1
    fi
}

# 主流程
main() {
    check_root
    confirm_uninstall
    remove_service
    remove_install_dir
    remove_service_user
    verify_uninstall
    echo -e "\n${GREEN}===== 卸载完成 =====${NC}"
}

main
