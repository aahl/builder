import React, { useCallback } from "react";
import { View, Switch, StyleSheet, Pressable, Animated } from "react-native";
import { useTVEventHandler } from "react-native";
import { ThemedText } from "@/components/ThemedText";
import { SettingsSection } from "./SettingsSection";
import { useSettingsStore } from "@/stores/settingsStore";
import { useButtonAnimation } from "@/hooks/useAnimation";
import { Colors } from "@/constants/Colors";

interface AdFilterSectionProps {
  onChanged: () => void;
  onFocus?: () => void;
  onBlur?: () => void;
}

export const AdFilterSection: React.FC<AdFilterSectionProps> = ({ onChanged, onFocus, onBlur }) => {
  const { adFilterEnabled, setAdFilterEnabled } = useSettingsStore();
  const [isFocused, setIsFocused] = React.useState(false);
  const animationStyle = useButtonAnimation(isFocused, 1.2);

  const handleToggle = useCallback(
    (enabled: boolean) => {
      setAdFilterEnabled(enabled);
      onChanged();
    },
    [setAdFilterEnabled, onChanged]
  );

  const handleSectionFocus = () => {
    setIsFocused(true);
    onFocus?.();
  };

  const handleSectionBlur = () => {
    setIsFocused(false);
    onBlur?.();
  };

  // TV遥控器事件处理
  const handleTVEvent = React.useCallback(
    (event: any) => {
      if (isFocused && event.eventType === "select") {
        handleToggle(!adFilterEnabled);
      }
    },
    [isFocused, adFilterEnabled, handleToggle]
  );

  useTVEventHandler(handleTVEvent);

  return (
    <SettingsSection focusable onFocus={handleSectionFocus} onBlur={handleSectionBlur}>
      <Pressable style={styles.settingItem} onFocus={handleSectionFocus} onBlur={handleSectionBlur}>
        <View style={styles.settingInfo}>
          <ThemedText style={styles.settingName}>播放去广告</ThemedText>
          <ThemedText style={styles.settingDescription}>
            自动剔除播放源中插入的广告片段（默认开启，异常时自动回退原播放地址）
          </ThemedText>
        </View>
        <Animated.View style={animationStyle}>
          <Switch
            value={adFilterEnabled}
            onValueChange={() => {}} // 禁用Switch的直接交互
            trackColor={{ false: "#767577", true: Colors.dark.primary }}
            thumbColor={adFilterEnabled ? "#ffffff" : "#f4f3f4"}
            pointerEvents="none"
          />
        </Animated.View>
      </Pressable>
    </SettingsSection>
  );
};

const styles = StyleSheet.create({
  settingItem: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingVertical: 12,
  },
  settingInfo: {
    flex: 1,
    marginRight: 12,
  },
  settingName: {
    fontSize: 16,
    fontWeight: "bold",
    marginBottom: 4,
  },
  settingDescription: {
    fontSize: 13,
    color: "#888",
  },
});
