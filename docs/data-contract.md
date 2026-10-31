# 数据约定

更新日期：2026-10-31。当前各文件都是第 1 版（`/1`）。改动格式的做法见 AGENTS.md。

## 原则

- 所有数据都是 UTF-8 JSON，放在用户授权的 `Documents/smartstudy/data/` 下，存储方式见 [架构决定 002](decisions/002-public-documents-storage-via-saf.md)。
- 每个文件都有 `schema` 字段，例如 `"smart-study.dictation/1"`。App 读取时先校验：
  - `schema` 不认识，或版本高于 App 支持的版本时，不加载也不覆盖这个文件，并提示升级；
  - 文件损坏时（包括不是 UTF-8 编码，例如在电脑上存成了 GBK），从临时文件或 `history/` 里找最近一个有效版本，提示后再使用。损坏的文件先保留一份：能按 UTF-8 读出的另存为 `history/<文件>/<时间>.broken`，不是 UTF-8 的原样改名为同目录下的 `<文件名>.<时间>.broken`。
- 时间统一使用带时区的 ISO 8601，例如 `2026-03-01T19:30:12+08:00`。
- 用户、词语、题目都用稳定 ID 关联；文件和目录名只用 ID，不用用户名。
- 每次作答追加一条记录（attempt），统计数字都从记录实时计算，不另外保存。
- 每次提交（一次听写、一次错题录入、一次练习等）是一个"学习次"（session）。同一次提交产生的记录都带上这个 session 的 ID，这样可以按次查看，也可以按次撤销。
- API Key 不写入这个文件夹。

## 目录结构

```text
Documents/smartstudy/
├── data/
│   ├── app.json                      用户列表和当前用户
│   ├── settings.json                 朗读、计时、模型、提示词覆盖
│   ├── users/<userId>/
│   │   ├── dictation-chinese.json    语文词语及作答记录
│   │   ├── dictation-english.json    英语词语及作答记录
│   │   └── error-book.json           英语错题及作答记录
│   └── history/                      各文件的历史版本，按原相对路径分目录，
│                                     文件名是保存时刻（UTC），例如 20261031-121503-123.json
```

## 文件格式

### app.json

```json
{
  "schema": "smart-study.app/1",
  "users": [
    { "id": "3f2c0a9e-…", "name": "小明", "createdAt": "2026-01-23T20:00:00+08:00" }
  ],
  "currentUserId": "3f2c0a9e-…"
}
```

用户名去掉首尾空格后不能为空，也不能重复。用户 `id` 同时是目录名 `data/users/<id>/`，只能包含字母、数字、`-` 和 `_`，不区分大小写也不能重复；App 新建用户时用 UUID。

### dictation-chinese.json / dictation-english.json

```json
{
  "schema": "smart-study.dictation/1",
  "language": "english",
  "sessions": [
    {
      "id": "s_91ab…",
      "kind": "dictation",
      "at": "2026-02-01T19:30:12+08:00",
      "readMode": "translation",
      "wordIds": ["w_5d1c…", "w_77e0…"]
    }
  ],
  "words": [
    {
      "id": "w_5d1c…",
      "text": "take care",
      "translation": "保重",
      "createdAt": "2026-02-01T19:30:12+08:00",
      "attempts": [
        { "sessionId": "s_91ab…", "correct": false },
        { "sessionId": "s_0c42…", "correct": true }
      ]
    }
  ]
}
```

- `text` 在同一文件内唯一。语文按去掉首尾空格后的文本比较；英语再忽略大小写、合并连续空格。
- `translation` 只在英语文件里出现。听写时译文为空才调用翻译，翻译结果写回这个字段。
- `attempts` 按时间升序排列。每条只记"属于哪一次"和"对不对"，时间和类型从所属的 session 取，这样不会出现两处不一致，文件也小约三成。
- `sessions` 按时间升序排列。`kind` 的取值：
  - `dictation`：新听写；
  - `review`：错词听写；
  - `added`：在错词本里手动添加，记为一次写错，这样它会参与错词听写的抽取；
  - `legacy`：类型未知的早期记录。
- `wordIds` 按这次听写的朗读顺序排列，"再听一遍这一次的词"时使用。`readMode` 只在英语文件里出现，`translation` 表示读中文写英文，`original` 表示读英文写英文。

### error-book.json

```json
{
  "schema": "smart-study.error-book/1",
  "sessions": [
    { "id": "s_3e5f…", "kind": "capture", "at": "2026-01-02T20:15:03+08:00", "questionIds": ["q_8a20…"] }
  ],
  "questions": [
    {
      "id": "q_8a20…",
      "text": "Which word has a different sound?\nA. c__a__ke\nB. m__a__p\nC. n__a__me",
      "answer": "B",
      "origin": "photo",
      "createdAt": "2026-01-02T20:15:03+08:00",
      "attempts": [
        { "sessionId": "s_3e5f…", "correct": false },
        { "sessionId": "s_6d10…", "correct": true }
      ]
    }
  ]
}
```

- `text` 保留换行，划线字母用 `__x__` 标记，显示时转成下划线。
- `answer` 是这道题当前的正确答案，只在题目上存一份。
- `origin` 为 `photo`（拍照识别）、`manual`（手动输入）或 `generated`（举一反三生成）。生成的题可以带 `sourceQuestionId` 指向原题。举一反三只保存做错的新题，保存时记一次 `variation` 作答。
- `sessions.kind` 的取值：`capture`（录入错题，记为一次做错）、`practice`（错题练习）、`variation`（举一反三练习）、`legacy`（类型未知的早期记录）。
- 录入时的重复检查：比较时去掉划线标记、空格和标点，并忽略大小写。规范化后相同的视为"已在错题本"，编辑距离相似度不低于 0.9 的视为"可能重复"。这两类题默认不勾选。
- 录入或举一反三保存时，如果一道题和错题本里已有的题规范化后相同（例如用户手动勾选了"已在错题本"的题），不新建题目，只给原来那道题记一次做错；原来的题干和答案不变。同一次提交里重复的题只记一次。
- 错题练习时，如果抽到的题在暂停期间被删除了，保存时跳过这些题，只记录还在的题。

### 删除与撤销规则

- 在错词本、错题本里删除：删掉整个词或整道题及其全部记录，也从各次 session 的列表中移除。
- 在学习记录里删除：只删掉所选词或题在所选日期范围内的作答记录，范围以外的记录保留。
- 撤销一次 session：删掉这次 session 和它产生的全部作答记录。这次听写里补上或改过的英语译文不会改回去。
- 删除或撤销之后，如果一个词或一道题没有任何记录，就把它一并删除；没有任何记录的 session 也一并删除。session 的 `wordIds` / `questionIds` 只保留在这次仍有作答记录的词或题。
- 每次删除或撤销都只写一次文件，写入前的版本保存在 `history/` 中。

### settings.json

```json
{
  "schema": "smart-study.settings/1",
  "tts": { "rate": 0.5, "pitch": 1.0, "repeatGapMs": { "chinese": 1000, "english": 1500 } },
  "timeLimitSeconds": { "dictation": 60, "errorBook": 120 },
  "models": {
    "vision": "qwen3.8-flash",
    "translation": "qwen3.8-flash",
    "generation": "qwen3.8-max",
    "generationTemperature": 1.2,
    "requestTimeoutSeconds": 45,
    "maxAttempts": 3
  },
  "prompts": {}
}
```

`prompts` 只保存和默认值不同的提示词，键名为 `chineseDictation`、`englishDictation`、`translation`、`errorBookRecognition`、`errorBookUnderline`、`questionGeneration`、`questionGenerationSystem`。听写识别和识别划线的提示词只覆盖"角色和任务"部分，代码会固定附加输出格式要求，以保证结果能被解析。识别划线时，模型只列出"画线所在的单词和画线的部分"，由代码把 `__x__` 标记放进题目原文。缺少的字段使用默认值。

## 抽样规则

错词听写、错题练习和举一反三共用一个抽样函数：

- 每个候选项的抽中概率 `p = clamp(max(错误率, 间隔天数 × 0.01), 0, 1)`。
- 错误率 = 做错次数 / 作答次数；间隔天数 = 今天与最近一次作答日期相差的自然日数。
- 按概率反复抽取，最多 `数量 × 20` 轮；仍不够时，从剩余候选项里随机补足；最后打乱顺序。
