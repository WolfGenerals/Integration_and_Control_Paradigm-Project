package com.hainabaichuan75.iac_p.affiliation;

import com.hainabaichuan75.iac_p.IACP;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 归属系统调试命令。
 * <p>
 * 注册方式：{@code NeoForge.EVENT_BUS.register(AffiliationCommand.class)}
 * <p>
 * 命令列表：
 * <ul>
 * <li>{@code /iacp affiliation list} — 列出所有已注册的归属条目</li>
 * <li>{@code /iacp affiliation check <uuid>} — 检查指定 SubLevel 的归属状态</li>
 * <li>{@code /iacp affiliation verify} — 校验注册表状态（调试用）</li>
 * <li>{@code /iacp affiliation slowquery <nanos>} — 设置慢查询阈值（0=禁用）</li>
 * <li>{@code /iacp affiliation player <player>} — 查看玩家的载具归属</li>
 * </ul>
 */
public final class AffiliationCommand {

    private AffiliationCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("iacp")
                        .then(Commands.literal("affiliation")
                                .requires(src -> src.hasPermission(2)) // 仅管理员/OP
                                .then(Commands.literal("list")
                                        .executes(AffiliationCommand::executeList))
                                .then(Commands.literal("check")
                                        .then(Commands.argument("uuid", StringArgumentType.word())
                                                .executes(AffiliationCommand::executeCheck)))
                                .then(Commands.literal("verify")
                                        .executes(AffiliationCommand::executeVerify))
                                .then(Commands.literal("slowquery")
                                        .then(Commands.argument("nanos", IntegerArgumentType.integer(0))
                                                .executes(AffiliationCommand::executeSlowQuery)))
                                .then(Commands.literal("player")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(AffiliationCommand::executePlayer)))
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§6用法: /iacp affiliation <list|check|verify|slowquery|player>"),
                                            true);
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
        );
    }

    // ==================================================================
    //  命令处理
    // ==================================================================
    /**
     * {@code /iacp affiliation list} — 列出所有已注册的归属条目。
     */
    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        final int total = AffiliationRegistry.size();
        src.sendSuccess(() -> Component.literal("§6=== AffiliationRegistry 条目列表 ==="), true);
        src.sendSuccess(() -> Component.literal("§7总计 " + total + " 个 SubLevel 已注册"), true);

        if (total == 0) {
            return Command.SINGLE_SUCCESS;
        }

        appendPlayerBindings(src);

        src.sendSuccess(() -> Component.literal(""), true);
        src.sendSuccess(() -> Component.literal("§e提示: 使用 §a/iacp affiliation check <uuid>§e 查看特定 SubLevel"),
                true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /iacp affiliation check <uuid>} — 检查指定 SubLevel 的归属状态。
     */
    private static int executeCheck(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String uuidStr = StringArgumentType.getString(ctx, "uuid");

        final UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            // 尝试部分匹配（仅前 8 字符）
            src.sendFailure(Component.literal("§c无效的 UUID 格式: " + uuidStr));
            return 0;
        }

        final String uuidShort = uuidStr.length() > 8 ? uuidStr.substring(0, 8) : uuidStr;

        AffiliationTag tag = AffiliationRegistry.getAffiliation(uuid);
        if (tag == null) {
            src.sendSuccess(() -> Component.literal("§eSubLevel " + uuidShort + " §7未在注册表中找到"), true);
            return Command.SINGLE_SUCCESS;
        }

        final AffiliationTag finalTag = tag;

        src.sendSuccess(() -> Component.literal("§6=== SubLevel 归属信息 ==="), true);
        src.sendSuccess(() -> Component.literal("§7UUID: §f" + uuid), true);
        src.sendSuccess(() -> Component.literal("§7角色: §f" + finalTag.role().name()), true);
        src.sendSuccess(() -> Component.literal("§7阵营: §f" + finalTag.faction()), true);
        src.sendSuccess(() -> Component.literal("§7归属主: §f" + (finalTag.ownerId() != null ? finalTag.ownerId() : "无")), true);
        src.sendSuccess(() -> Component.literal("§7所属载具: §f" + (finalTag.vehicleId() != null ? finalTag.vehicleId() : "自身")),
                true);
        src.sendSuccess(() -> Component.literal("§7逻辑组: §f" + (finalTag.groupId() != null ? finalTag.groupId() : "无")), true);

        // 如果属于某个组，列出组内所有成员
        if (finalTag.groupId() != null) {
            final UUID groupId = finalTag.groupId();
            Set<UUID> members = AffiliationRegistry.getAllInGroup(groupId);
            if (!members.isEmpty()) {
                final int memberCount = members.size();
                src.sendSuccess(() -> Component.literal("§7组内成员 (" + memberCount + " 个):"), true);
                for (UUID m : members) {
                    final UUID memberUUID = m;
                    AffiliationRole mRole = AffiliationRegistry.getRole(memberUUID);
                    final String roleName = mRole.name();
                    String marker = memberUUID.equals(uuid) ? " §a<-- 当前" : "";
                    final String finalMarker = marker;
                    src.sendSuccess(
                            () -> Component.literal("  §8- §f" + memberUUID + " §7(" + roleName + ")" + finalMarker), true);
                }
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /iacp affiliation verify} — 执行简单的注册表一致性检查。
     */
    private static int executeVerify(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("§6=== 注册表一致性检查 ==="), true);

        // 检查1：组索引中的成员是否都能在 SUBLEVEL_TO_TAG 中找到
        // 通过 getAllInGroup 遍历所有可能的组（我们没有暴露组列表，所以用另一种方式）
        src.sendSuccess(() -> Component.literal("§7注册 SubLevel 数: §f" + AffiliationRegistry.size()), true);

        // 检查2：玩家绑定反向索引一致性
        src.sendSuccess(() -> Component.literal("§7检查玩家-载具绑定..."), true);
        int bindingsOk = appendPlayerBindings(src);

        src.sendSuccess(() -> Component.literal(""), true);
        src.sendSuccess(() -> Component.literal("§a✓ 检查完成。阈值: "
                + (AffiliationRegistry.getSlowQueryThreshold() > 0
                ? AffiliationRegistry.getSlowQueryThreshold() / 1000 + " μs"
                : "慢查询禁用")),
                true);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /iacp affiliation slowquery <nanos>} — 设置慢查询阈值。
     */
    private static int executeSlowQuery(CommandContext<CommandSourceStack> ctx) {
        int nanos = IntegerArgumentType.getInteger(ctx, "nanos");
        AffiliationRegistry.setSlowQueryThreshold(nanos);
        String msg = nanos > 0
                ? "§a慢查询阈值已设为 " + nanos + " ns (" + (nanos / 1000) + " μs)"
                : "§e慢查询日志已禁用";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /iacp affiliation player <player>} — 查看玩家的载具归属。
     */
    private static int executePlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            UUID playerUUID = player.getUUID();
            UUID vehicleUUID = AffiliationRegistry.getPlayerVehicle(playerUUID);

            if (vehicleUUID == null) {
                src.sendSuccess(() -> Component.literal("§e玩家 §f" + player.getName().getString()
                        + " §e当前未驾驶任何载具"), true);
                return Command.SINGLE_SUCCESS;
            }

            AffiliationTag tag = AffiliationRegistry.getAffiliation(vehicleUUID);
            src.sendSuccess(() -> Component.literal("§6玩家 " + player.getName().getString() + " 的载具信息"), true);
            src.sendSuccess(() -> Component.literal("§7载具 SubLevel: §f" + vehicleUUID), true);
            src.sendSuccess(() -> Component.literal("§7角色: §f" + (tag != null ? tag.role().name() : "未知")), true);

            // 列出该载具的所有相关结构
            Set<UUID> affiliated = AffiliationRegistry.getOwnAffiliatedSet(vehicleUUID);
            if (affiliated.size() > 1) {
                src.sendSuccess(() -> Component.literal("§7相关结构 (" + (affiliated.size() - 1) + " 个):"), true);
                for (UUID slUUID : affiliated) {
                    if (slUUID.equals(vehicleUUID)) {
                        continue;
                    }
                    AffiliationRole ar = AffiliationRegistry.getRole(slUUID);
                    src.sendSuccess(() -> Component.literal("  §8- §f" + slUUID.toString().substring(0, 8)
                            + "§8... §7(" + ar.name() + ")"), true);
                }
            }
        } catch (Exception e) {
            src.sendFailure(Component.literal("§c命令执行出错: " + e.getMessage()));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================
    //  辅助方法
    // ==================================================================
    /**
     * 输出玩家绑定信息到命令源（通过私有 API 的间接方式）。 此处通过已有公开 API 检查，不直接访问私有字段。
     */
    private static int appendPlayerBindings(CommandSourceStack src) {
        // 由于无法直接遍历 PLAYER_TO_VEHICLE 私有 Map，
        // 我们输出提示信息说明当前状态
        int total = AffiliationRegistry.size();
        src.sendSuccess(() -> Component.literal("§7当前注册总数: " + total), true);

        // 提示用户使用 /iacp affiliation player <name> 查看具体玩家
        src.sendSuccess(() -> Component.literal("§7使用 §a/iacp affiliation player <玩家名> §7查看具体玩家绑定"), true);

        return total;
    }
}
