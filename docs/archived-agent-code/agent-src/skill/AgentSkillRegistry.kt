package com.taostudio.tapaccounting.chat.agent.skill

import com.taostudio.tapaccounting.chat.agent.AgentToolRegistry

object AgentSkillRegistry {
    private val skills = mutableMapOf<String, AgentSkill>()

    fun register(skill: AgentSkill) {
        val existing = skills[skill.id]
        if (existing != null) {
            return
        }
        skills[skill.id] = skill
    }

    fun findById(id: String): AgentSkill? = skills[id]

    fun getAll(): List<AgentSkill> = skills.values.toList()

    fun getToolsForSkills(skillIds: Set<String>): List<String> {
        return skillIds.flatMap { id ->
            skills[id]?.toolIds ?: emptySet()
        }.distinct()
    }

    fun hasSkill(id: String): Boolean = skills.containsKey(id)

    fun clear() {
        skills.clear()
    }
}
