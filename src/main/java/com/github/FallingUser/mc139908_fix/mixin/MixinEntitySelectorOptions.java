package com.github.FallingUser.mc139908_fix.mixin;

import com.google.common.collect.Maps;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Predicate;

@Mixin(EntitySelectorOptions.class)
public abstract class MixinEntitySelectorOptions {

    @Invoker("register")
    private static void callRegister(String name, EntitySelectorOptions.Modifier modifier,
                                     Predicate<EntitySelectorParser> canUse, Component description) {
        throw new AssertionError();
    }

    @Redirect(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions;register(Ljava/lang/String;Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions$Modifier;Ljava/util/function/Predicate;Lnet/minecraft/network/chat/Component;)V"))
    private static void redirectRegister(String name, EntitySelectorOptions.Modifier modifier, Predicate<EntitySelectorParser> predicate, Component description) {
        if ("advancements".equals(name)) {
            modifier = createAdvancementsModifier();
        }
        callRegister(name, modifier, predicate, description);
    }

    @Unique
    private static EntitySelectorOptions.Modifier createAdvancementsModifier() {
        return parser -> {
            StringReader reader = parser.getReader();
            Map<Identifier, Predicate<AdvancementProgress>> expected = Maps.newHashMap();
            reader.expect('{');
            reader.skipWhitespace();

            while (reader.canRead() && reader.peek() != '}') {
                reader.skipWhitespace();
                Identifier advancementId = Identifier.read(reader);
                reader.skipWhitespace();
                reader.expect('=');
                reader.skipWhitespace();

                if (reader.canRead() && reader.peek() == '{') {
                    Map<String, Predicate<CriterionProgress>> criteria = Maps.newHashMap();
                    reader.skipWhitespace();
                    reader.expect('{');
                    reader.skipWhitespace();

                    while (reader.canRead() && reader.peek() != '}') {
                        reader.skipWhitespace();
                        // 修复点：使用支持冒号等字符的criterion名称读取器
                        String criterionName = readCriterionName(reader);
                        reader.skipWhitespace();
                        reader.expect('=');
                        reader.skipWhitespace();
                        boolean value = reader.readBoolean();
                        criteria.put(criterionName, progress -> progress.isDone() == value);
                        reader.skipWhitespace();
                        if (reader.canRead() && reader.peek() == ',') {
                            reader.skip();
                        }
                    }

                    reader.skipWhitespace();
                    reader.expect('}');
                    reader.skipWhitespace();

                    expected.put(advancementId, progress -> {
                        for (Map.Entry<String, Predicate<CriterionProgress>> entry : criteria.entrySet()) {
                            CriterionProgress criterionProgress = progress.getCriterion(entry.getKey());
                            if (criterionProgress == null || !entry.getValue().test(criterionProgress)) {
                                return false;
                            }
                        }
                        return true;
                    });
                } else {
                    boolean value = reader.readBoolean();
                    expected.put(advancementId, progress -> progress.isDone() == value);
                }

                reader.skipWhitespace();
                if (reader.canRead() && reader.peek() == ',') {
                    reader.skip();
                }
            }

            reader.expect('}');

            if (!expected.isEmpty()) {
                parser.addPredicate(entity -> {
                    if (!(entity instanceof ServerPlayer player)) return false;
                    PlayerAdvancements advancements = player.getAdvancements();
                    ServerAdvancementManager manager = player.level().getServer().getAdvancements();
                    for (Map.Entry<Identifier, Predicate<AdvancementProgress>> entry : expected.entrySet()) {
                        AdvancementHolder advancement = manager.get(entry.getKey());
                        if (advancement == null || !entry.getValue().test(advancements.getOrStartProgress(advancement))) {
                            return false;
                        }
                    }
                    return true;
                });
                parser.setIncludesEntities(false);
            }
            parser.setHasAdvancements(true);
        };
    }

    @Unique
    private static String readCriterionName(StringReader reader) throws CommandSyntaxException {
        if (StringReader.isQuotedStringStart(reader.peek())) {
            return reader.readString();
        }
        int start = reader.getCursor();
        while (reader.canRead()) {
            char c = reader.peek();
            if (!isAllowedInUnquotedString(c) || Character.isWhitespace(c)) {
                break;
            }
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Unique
    private static boolean isAllowedInUnquotedString(final char c) {
        return c >= '0' && c <= '9'
                || c >= 'A' && c <= 'Z'
                || c >= 'a' && c <= 'z'
                || c == '_' || c == '-'
                || c == '.' || c == '+'
                || c == ':';
    }
}
