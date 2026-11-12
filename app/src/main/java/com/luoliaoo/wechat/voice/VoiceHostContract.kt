package com.luoliaoo.wechat.voice

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * v10.0.0: 微信语音转写组件的**反射契约**。
 *
 * 【来源】对照参考实现 `yanwai-main/voice/VoiceHostContract.kt` 的写法：不硬编码任何混淆类名，
 * 只用**方法签名形状**去认三个方法。这是本项目 Resolver 一贯的做法（见 Resolver.kt 的注释：
 * "found by SHAPE, so no obfuscated name is involved and every WeChat version works"）。
 *
 * 【三个方法的形状】
 *   submit : (非基本类型, boolean, int, int) -> void    发起转写；第 0 参就是 MsgInfo 类
 *   state  : (long) -> enum，且枚举常量必须包含
 *            NoTransform / PreTransform / Transforming / Transformed
 *   text   : (long, String) -> String                  取转写结果（msgId + 语音文件名）
 *
 * 【为什么必须"唯一命中"才算成功】
 * 三个方法全部用 `single` 而不是 `first`：如果形状匹配到两个候选，说明这个版本的结构与预期不符，
 * 此时**赌一个**可能把参数传错对象，风险远大于"这个功能不可用"。所以宁可返回 null，
 * 让上层如实报「当前微信版本的语音转写尚未适配」，而不是悄悄调错方法。
 *
 * 【chatField 是干什么的】
 * 组件实例持有它所属的 ChattingContext 字段。多个聊天可能各有一个组件实例，
 * 靠这个字段才能判断"哪个实例属于哪个聊天"。单个字段找不到（0 个或多于 1 个）同样判失败。
 */
data class VoiceHostContract(
    val submit: Method,
    val state: Method,
    val text: Method,
    val chatField: Field,
) {
    /** 发起转写时要传的消息对象类型（= submit 的第 0 个参数类型）。 */
    val messageClass: Class<*> get() = submit.parameterTypes[0]

    companion object {
        /**
         * @param component 转写组件类（由 DexKit 定位）
         * @param chat      聊天上下文类（由 DexKit 定位）
         * @return 契约；任一形状不唯一/不满足即返回 null（fail-closed）
         */
        fun resolve(component: Class<*>, chat: Class<*>): VoiceHostContract? = runCatching {
            val methods = component.methods.filter { !Modifier.isStatic(it.modifiers) }

            val submit = methods.single { m ->
                val p = m.parameterTypes
                p.size == 4 &&
                    !p[0].isPrimitive &&
                    p[1] == Boolean::class.javaPrimitiveType &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType &&
                    m.returnType == Void.TYPE
            }

            val state = methods.single { m ->
                m.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                    m.returnType.isEnum &&
                    m.returnType.enumConstants.orEmpty()
                        .map { (it as Enum<*>).name }
                        .containsAll(TRANSFORM_STATES)
            }

            val text = methods.single { m ->
                m.parameterTypes.contentEquals(
                    arrayOf(Long::class.javaPrimitiveType, String::class.java),
                ) && m.returnType == String::class.java
            }

            val field = generateSequence(component) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { it.type == chat && !Modifier.isStatic(it.modifiers) }
                .apply { isAccessible = true }

            VoiceHostContract(
                submit.apply { isAccessible = true },
                state.apply { isAccessible = true },
                text.apply { isAccessible = true },
                field,
            )
        }.getOrNull()

        /** 状态机的四个状态名。`containsAll` 而不是 `contentEquals`：允许枚举里还有别的常量。 */
        private val TRANSFORM_STATES =
            listOf("NoTransform", "PreTransform", "Transforming", "Transformed")
    }
}
