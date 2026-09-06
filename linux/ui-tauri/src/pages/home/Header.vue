<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { SolarArrowRightLeft } from "@/lib/solarIcons";
import VortexLogo from "@/components/VortexLogo.vue";

const { t } = useI18n();
</script>

<template>
  <header class="flex items-center gap-3 px-5 py-4 border-b border-border bg-card/30 backdrop-blur">
    <VortexLogo class="h-9 w-9 text-primary" />
    <div class="flex-1">
      <h1 class="text-base font-semibold leading-tight flex items-center gap-2">
        {{ t("app.title") }}
        <span class="text-muted-foreground">
          <SolarArrowRightLeft class="h-3.5 w-3.5 inline" />
        </span>
      </h1>
      <p class="text-xs text-muted-foreground">{{ t("app.tagline") }}</p>
    </div>
  </header>
</template>
