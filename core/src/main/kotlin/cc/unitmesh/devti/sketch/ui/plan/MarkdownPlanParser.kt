package cc.unitmesh.devti.sketch.ui.plan

import cc.unitmesh.devti.observer.plan.AgentPlan
import cc.unitmesh.devti.observer.plan.PlanTask
import cc.unitmesh.devti.observer.plan.TaskStatus
import com.intellij.openapi.diagnostic.logger
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.accept
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.ast.visitors.RecursiveVisitor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown计划解析器，负责将markdown格式的计划文本解析为PlanItem对象列表
 *
 * 示例：
 *
 * ```markdown
 * 1. 领域模型重构：
 *   - [✓] 将BlogPost实体合并到Blog聚合根，建立完整的领域对象
 *   - [*] 添加领域行为方法（发布、审核、评论等）
 *   - [!] 修复数据模型冲突
 *   - [ ] 实现新增的领域服务
 * 2. 分层结构调整：
 *   - [ ] 清理entity层冗余对象
 * ```
*/
object MarkdownPlanParser {
    private val LOG = logger<MarkdownPlanParser>()
    private val ROOT_ELEMENT_TYPE = IElementType("ROOT")
    private val CHECKMARK = "✓"
    private val GITHUB_TODO_PATTERN = Regex("^\\s*-\\s*\\[\\s*([xX!*✓]?)\\s*\\]\\s*(.*)")
    private val GITHUB_TODO_COMPLETED = listOf("x", "X", "✓")
    private val GITHUB_TODO_FAILED = listOf("!")
    private val GITHUB_TODO_IN_PROGRESS = listOf("*")

    /**
     * 解析markdown文本为计划项列表
     * @param content markdown格式的计划文本
     * @return 解析得到的计划项列表，若解析失败则返回空列表
     */
    fun parse(content: String): List<AgentPlan> {
        try {
            val flavour = GFMFlavourDescriptor()
            val parsedTree = MarkdownParser(flavour).parse(ROOT_ELEMENT_TYPE, content)
            return parsePlanItems(parsedTree, content)
        } catch (e: Exception) {
            LOG.warn("Failed to parse markdown plan content", e)
            return emptyList()
        }
    }

    private fun parsePlanItems(node: ASTNode, content: String): List<AgentPlan> {
        val agentPlans = mutableListOf<AgentPlan>()
        val topLevelOrderedLists = findTopLevelOrderedLists(node)
        if (topLevelOrderedLists.isNotEmpty() && isFlatOrderedList(topLevelOrderedLists.first(), content)) {
            processFlatOrderedList(topLevelOrderedLists.first(), content, agentPlans)
        } else {
            processSectionedList(node, content, agentPlans)
        }
        
        return agentPlans
    }
    
    private fun findTopLevelOrderedLists(node: ASTNode): List<ASTNode> {
        val orderedLists = mutableListOf<ASTNode>()
        
        node.children.forEach { child ->
            if (child.type == MarkdownElementTypes.ORDERED_LIST) {
                orderedLists.add(child)
            }
        }

        return orderedLists
    }

    private fun processFlatOrderedList(node: ASTNode, content: String, agentPlans: MutableList<AgentPlan>) {
        node.children.forEach { listItemNode ->
            if (listItemNode.type == MarkdownElementTypes.LIST_ITEM) {
                val listItemText = listItemNode.getTextInNode(content).toString().trim()
                val titleMatch = "^(\\d+)\\.\\s*(.+?)(?:\\s*$CHECKMARK)?$".toRegex().find(listItemText)

                if (titleMatch != null) {
                    val title = titleMatch.groupValues[2].trim()
                    val completed = listItemText.contains(CHECKMARK)
                    agentPlans.add(AgentPlan(title, emptyList(), completed))
                }
            }
        }
    }

    private fun processSectionedList(node: ASTNode, content: String, agentPlans: MutableList<AgentPlan>) {
        var currentSectionTitle = ""
        var currentSectionItems = mutableListOf<PlanTask>()
        var currentSectionCompleted = false
        var currentSectionStatus = TaskStatus.TODO

        node.accept(object : RecursiveVisitor() {
            override fun visitNode(node: ASTNode) {
                when (node.type) {
                    MarkdownElementTypes.ORDERED_LIST -> {
                        node.children.forEach { listItemNode ->
                            if (listItemNode.type == MarkdownElementTypes.LIST_ITEM) {
                                // Extract just the first line for the section title
                                val listItemFullText = listItemNode.getTextInNode(content).toString().trim()
                                val firstLineEnd = listItemFullText.indexOf('\n')
                                val listItemFirstLine = if (firstLineEnd > 0) {
                                    listItemFullText.substring(0, firstLineEnd).trim()
                                } else {
                                    listItemFullText
                                }

                                // Check for section status marker like "1. Section Title [✓]"
                                val sectionStatusMatch = "^(\\d+)\\.\\s*(.+?)(?:\\s*\\[(.)\\])?$".toRegex().find(listItemFirstLine)
                                
                                if (sectionStatusMatch != null) {
                                    // Save previous section if exists
                                    if (currentSectionTitle.isNotEmpty()) {
                                        // Create new PlanList with stored data
                                        val newAgentPlan = AgentPlan(
                                            currentSectionTitle,
                                            currentSectionItems.toList(),
                                            currentSectionCompleted,
                                            currentSectionStatus
                                        )
                                        
                                        // Update section completion status based on tasks
                                        newAgentPlan.updateCompletionStatus()
                                        
                                        agentPlans.add(newAgentPlan)
                                        currentSectionItems = mutableListOf()
                                    }

                                    // Extract the title without any status marker
                                    currentSectionTitle = sectionStatusMatch.groupValues[2].trim()
                                    
                                    // Check for section status marker
                                    val statusMarker = sectionStatusMatch.groupValues.getOrNull(3)
                                    currentSectionCompleted = statusMarker == "✓" || statusMarker == "x" || statusMarker == "X"
                                    currentSectionStatus = when (statusMarker) {
                                        "✓", "x", "X" -> TaskStatus.COMPLETED
                                        "!" -> TaskStatus.FAILED
                                        "*" -> TaskStatus.IN_PROGRESS
                                        else -> TaskStatus.TODO
                                    }

                                    // Process child nodes for tasks
                                    listItemNode.children.forEach { childNode ->
                                        if (childNode.type == MarkdownElementTypes.UNORDERED_LIST) {
                                            processTaskItems(childNode, content, currentSectionItems)
                                        }
                                    }
                                }
                            }
                        }
                        // Skip recursive processing for ORDERED_LIST nodes since we've already processed them
                        // Don't call super.visitNode for this type to avoid double-processing
                    }
                    MarkdownElementTypes.UNORDERED_LIST -> {
                        processTaskItems(node, content, currentSectionItems)
                        // Skip recursive processing for UNORDERED_LIST nodes
                        // Don't call super.visitNode for this type to avoid double-processing
                    }
                    else -> {
                        // Only continue recursion for other node types
                        super.visitNode(node)
                    }
                }
            }
        })

        // 添加最后一个章节（如果有）
        if (currentSectionTitle.isNotEmpty()) {
            // Create new PlanList with stored data
            val newAgentPlan = AgentPlan(
                currentSectionTitle,
                currentSectionItems.toList(),
                currentSectionCompleted,
                currentSectionStatus
            )
            
            // Update section completion status based on tasks
            newAgentPlan.updateCompletionStatus()
            
            agentPlans.add(newAgentPlan)
        }
    }

    private fun isFlatOrderedList(node: ASTNode, content: String): Boolean {
        var hasNestedLists = false
        
        node.children.forEach { listItemNode ->
            if (listItemNode.type == MarkdownElementTypes.LIST_ITEM) {
                listItemNode.children.forEach { childNode ->
                    if (childNode.type == MarkdownElementTypes.UNORDERED_LIST) {
                        hasNestedLists = true
                    }
                }
            }
        }
        
        return !hasNestedLists
    }

    private fun processTaskItems(listNode: ASTNode, content: String, itemsList: MutableList<PlanTask>) {
        listNode.children.forEach { taskItemNode ->
            if (taskItemNode.type == MarkdownElementTypes.LIST_ITEM) {
                val taskText = taskItemNode.getTextInNode(content).toString().trim()
                // Extract just the first line for the task
                val firstLineEnd = taskText.indexOf('\n')
                val taskFirstLine = if (firstLineEnd > 0) {
                    taskText.substring(0, firstLineEnd).trim()
                } else {
                    taskText
                }
                
                // Check for GitHub style TODO
                val githubTodoMatch = GITHUB_TODO_PATTERN.find(taskFirstLine)
                if (githubTodoMatch != null) {
                    // Extract the task text and preserve the checkbox status
                    val checkState = githubTodoMatch.groupValues[1]
                    val todoText = githubTodoMatch.groupValues[2].trim()
                    
                    // Determine task status based on marker
                    val formattedTask = when {
                        checkState in GITHUB_TODO_COMPLETED -> "[✓] $todoText"
                        checkState in GITHUB_TODO_FAILED -> "[!] $todoText"
                        checkState in GITHUB_TODO_IN_PROGRESS -> "[*] $todoText"
                        else -> "[ ] $todoText"
                    }
                    
                    itemsList.add(PlanTask.fromText(formattedTask))
                } else {
                    // Process task text and retain the checkmark in the text (original behavior)
                    val cleanTaskText = taskFirstLine.replace(Regex("^[\\-\\*]\\s+"), "").trim()
                    if (cleanTaskText.isNotEmpty()) {
                        // If there's no explicit checkbox, treat as completed
                        itemsList.add(PlanTask(cleanTaskText, true, TaskStatus.COMPLETED))
                    }
                }
            }
        }
    }
}
