---
title: 把网易云音乐、QQ音乐的歌单同步到 Spotify、Apple Music 或 YouTube Music
description: 用 Trackhop 把公开的网易云音乐或 QQ音乐歌单搬到 Spotify、Apple Music、YouTube Music、TIDAL 或你自己的音乐服务器：只需歌单链接，无需登录。免费、开源。
lang: zh
---

# 网易云音乐、QQ音乐：公开歌单作为同步来源

[English](netease-qq-music)

网易云音乐和 QQ音乐都没有面向第三方应用的官方接口，不过这件事并不需要：一个**公开歌单的链接**就可以作为同步的来源，
目标可以是 Spotify、Apple Music、YouTube Music、TIDAL、Deezer、Navidrome、Jellyfin、Plex 或一个歌单文件，全程不需要登录国内账号。

仅在 GitHub/F-Droid 版本、Docker 版本和从本站下载的 Trackhop Desktop 中提供。Google Play 和华为 AppGallery
版本需要安装 [Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge/releases/latest) 插件才能读取这些链接。
Microsoft Store 版本只包含官方接口，因此没有这项功能。

## 使用链接

1. 在网易云音乐或 QQ音乐 App 里打开歌单，**分享 → 复制链接**。支持这些形式：
   - 网易云音乐：`https://music.163.com/#/playlist?id=…`、`https://music.163.com/playlist?id=…`、
     手机版 `https://y.music.163.com/m/playlist?id=…`，以及 App 分享文字里的
     `https://music.163.com/playlist/…/…/?userid=…`；
   - QQ音乐：`https://y.qq.com/n/ryqq/playlist/…`、旧版 `https://y.qq.com/n/yqq/playlist/….html`，
     以及 App 的分享链接（`https://i.y.qq.com/n2/m/share/details/taoge.html?…&id=…`）。
2. 在 Trackhop 里新建一个同步，把链接粘贴为来源（或者直接从音乐 App 分享到 Trackhop：编辑器会带着链接打开）。像平常一样选择目标。
3. 之后这个同步和其他同步一样运行，可以手动，也可以按计划：国内这边的歌单变长，另一边也跟着变长。

在[服务器版](docker)和 Trackhop Desktop 里，新建同步时把来源选为 **NetEase Cloud Music (public links, unofficial)**
或 **QQ Music (public links, unofficial)**，下一步粘贴链接即可。

## 会发生什么

- **只读。** 网易云音乐和 QQ音乐永远不会作为目标：不会向它们写入任何内容，Trackhop 也从不要求登录国内账号。
- **匹配**依据歌名、歌手和时长；两边都没有 ISRC。中、日、韩语发行的歌曲在 Spotify、Apple Music 和 YouTube Music
  上通常是同样的歌名，所以大多数歌曲会直接匹配；在其他平台用了拼音或翻译歌名的歌曲会进入复核页面，由你挑选正确的那一首。
- **灰色歌曲。** 网易云里已经下架的歌曲照样会列出并标记为不可用，就像 Spotify 里的灰色歌曲一样：仍然会在目标平台上搜索它。
- **很长的歌单**会完整读取，最多 10,000 首（网易云一次列出全部 id，再分批取详情；QQ音乐每次给 30 首）。

## 出错时

- *"This NetEase Cloud Music playlist is private or doesn't exist: only public links work"*（QQ音乐同理）：歌单是私密的、已删除，
  或者链接有误。请歌单的主人设为公开，或者重新从 App 分享一次。
- *"NetEase Cloud Music changed its site; Trackhop needs an update"*（QQ音乐同理）：Trackhop 按网站自己的页面方式读取链接，
  而不是通过官方接口，网站改版了。等新版本发布后更新 Trackhop，或者[反馈问题](reporting-a-problem)。
- 链接能识别但属于另一个服务：先选对来源。在 App 里来源由链接本身决定，所以这种情况只会出现在服务器版的表单里。

[返回指南](./)
