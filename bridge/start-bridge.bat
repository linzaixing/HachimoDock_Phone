@echo off
chcp 65001 >nul
title HachimoPhone PC 端
rem 一键启动：Claude / WorkBuddy hooks 刷新 + Bridge(8765) + Codex / Trae 监听，单窗口
cd /d "%~dp0.."
node bridge/start-all.mjs
echo.
echo 已停止，按任意键关闭窗口。
pause >nul
