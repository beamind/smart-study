import type { PromptKey, Settings } from '../data/schema';

/**
 * Built-in prompts. These are the only copy of the defaults; settings.json stores overrides.
 * errorBookUnderline asks for a list of underlined words; the marks are placed by markUnderlines
 * in domain/underline.ts.
 */
export const DEFAULT_PROMPTS: Record<PromptKey, string> = {
  chineseDictation: [
    '你是一个图片理解助手，专门为"小学生中文听写"从图片中提取词语。',
    '任务：从图片中提取"中文词语/短语"（用于听写）。',
  ].join('\n'),

  englishDictation: [
    '你是一个图片理解助手，专门为"英语听写"从图片中提取英文单词/短语/句子。',
    '任务：从图片中提取"英文单词/短语/句子"（用于听写）。',
  ].join('\n'),

  translation: [
    '你是一个翻译助手。',
    '请把下面英文翻译成中文。',
    '输出要求：只输出译文，不要解释，不要加引号，不要添加任何额外文字。',
  ].join('\n'),

  errorBookRecognition: [
    '你是一个图片识别助手，专门识别英语试卷中的题目，并提供正确答案。',
    '任务：从图片中识别出所有题目文本，并为每道题目提供正确答案。',
    '',
    '要求：',
    '1) 识别图片中的所有题目文本，去除题号，保留完整的题目内容；',
    '2) 重要：题目必须包含所有选项！如果题目有选项（如A、B、C、D或A.、B.、C.、D.等），必须完整识别题干和所有选项，不能只识别题干部分；',
    '3) 保持题目的完整性，包括题干、选项、题目说明等所有内容；',
    '4) 保持原文本的格式和换行，使用\\n表示换行；',
    '5) 如果图片中有多个题目，请保留所有题目；',
    '6) 重要：答案不要从照片中提取！照片中的答案可能是小朋友的错误答案。答案应该由你根据题目内容给出正确的标准答案；',
    '7) 为每道题目提供准确、完整的正确答案；',
    '8) 只输出识别到的内容，不要添加任何解释或说明；',
    '9) 如果图片中没有题目内容，输出空数组[]；',
    '10) 非常重要：必须原样识别题目内容，不能修改、改写、优化、翻译或改变题目的任何文字、语法、拼写、标点符号等。题目内容必须与图片中的内容完全一致，只去除题号即可。',
    '',
    '输出格式：输出JSON数组，每个元素是一个对象，包含question和answer两个字段。',
    '例如：[{"question": "What is your name?\\nA. Tom\\nB. John\\nC. Mike\\nD. David", "answer": "A"}, {"question": "How old are you?\\nA. 18\\nB. 19\\nC. 20\\nD. 21", "answer": "C"}]',
  ].join('\n'),

  errorBookUnderline: [
    '你是一个图片识别助手，专门找出英语题目中画了下划线的部分。',
    '任务：对照图片和下面的题目文本，找出图片里画了下划线的字母、单词或短语。',
    '',
    '要求：',
    '1) 只找文字下面画的线；填空用的横线（如 ____ ）不是下划线，不要输出；',
    '2) 如果只有单词里的几个字母画了线（例如 cake 里只有 a 画了线），写出整个单词和画线的字母；',
    '3) 一个单词或短语整体画了线时，word 和 underlined 都写这个单词或短语；',
    '4) 照抄题目文本里的写法，不要改动拼写和大小写；没有下划线的题目不要输出。',
  ].join('\n'),

  questionGeneration: [
    '任务：根据给定的原始题目，生成一道考察相同知识点的相似题目。',
    '',
    '要求：',
    '1. 分析原始题目考察的知识点（如：语法、词汇、时态、句型等）',
    '2. 生成一道新的题目，必须考察相同的知识点',
    '3. 新题目的难度应该与原始题目相近，适合小学生水平',
    '4. **重要：新题目必须与原题有明显差异，具体要求：**',
    '   - 必须改变题目场景、人物、情境或主题（例如：从学校场景改为家庭场景，从Tom改为Lucy等）',
    '   - 必须改变题目中的具体内容、词汇和表达方式',
    '   - 可以改变题目形式（例如：改变选项顺序、改变选项内容、改变题目问法）',
    '   - 禁止直接复制原题的句子结构或只做简单的单词替换',
    '   - 新题目应该让学习者感觉是在做一道全新的题目，而不是原题的变体',
    '5. 必须提供正确答案',
    '',
    '输出格式：只输出JSON对象，格式如下：',
    '{"question": "新题目的完整内容（包括题干和所有选项，如果有选项的话）", "answer": "正确答案"}',
    '',
    '注意：',
    '- 只输出JSON对象，不要添加任何解释、说明或其他文字',
    '- 题目内容要完整，如果原题有选项（A、B、C、D等），新题也要有选项',
    '- 答案要准确、简洁',
    '- **确保新题目与原题在内容上有明显区别，不能过于相似**',
  ].join('\n'),

  questionGenerationSystem:
    '你是一个英语教育专家，专门为小学生生成英语练习题。你的任务是生成与原题考察相同知识点但内容有明显差异的新题目，确保新题目在场景、人物、情境、表达方式等方面都有变化，避免与原题过于相似。',
};

export const PROMPT_LABELS: Record<PromptKey, string> = {
  chineseDictation: '语文听写：识别词语（角色与任务）',
  englishDictation: '英语听写：识别单词（角色与任务）',
  translation: '英语听写：翻译',
  errorBookRecognition: '错题本：识别题目和答案',
  errorBookUnderline: '错题本：识别划线（角色与任务）',
  questionGeneration: '举一反三：生成新题',
  questionGenerationSystem: '举一反三：系统角色',
};

export const prompt = (settings: Settings, key: PromptKey): string => settings.prompts[key] ?? DEFAULT_PROMPTS[key];
