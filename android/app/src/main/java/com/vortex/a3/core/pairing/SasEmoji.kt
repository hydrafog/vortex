package com.vortex.a3.core.pairing

object SasEmoji {
    data class Glyph(val emoji: String, val name: String)

    val TABLE: List<Glyph> = listOf(
        Glyph("🦊", "Fox"), Glyph("🐼", "Panda"), Glyph("🦁", "Lion"), Glyph("🐯", "Tiger"),
        Glyph("🐶", "Dog"), Glyph("🐱", "Cat"), Glyph("🐵", "Monkey"), Glyph("🐸", "Frog"),
        Glyph("🐧", "Penguin"), Glyph("🦉", "Owl"), Glyph("🦅", "Eagle"), Glyph("🐝", "Bee"),
        Glyph("🦋", "Butterfly"), Glyph("🐢", "Turtle"), Glyph("🐙", "Octopus"), Glyph("🐬", "Dolphin"),
        Glyph("🐳", "Whale"), Glyph("🦈", "Shark"), Glyph("🐠", "Fish"), Glyph("🦀", "Crab"),
        Glyph("🐌", "Snail"), Glyph("🐞", "Ladybug"), Glyph("🦄", "Unicorn"), Glyph("🐴", "Horse"),
        Glyph("🐮", "Cow"), Glyph("🐷", "Pig"), Glyph("🐰", "Rabbit"), Glyph("🐨", "Koala"),
        Glyph("🐻", "Bear"), Glyph("🦒", "Giraffe"), Glyph("🐘", "Elephant"), Glyph("🦓", "Zebra"),
        Glyph("🦔", "Hedgehog"), Glyph("🦇", "Bat"), Glyph("🦜", "Parrot"), Glyph("🦚", "Peacock"),
        Glyph("🍎", "Apple"), Glyph("🍌", "Banana"), Glyph("🍓", "Strawberry"), Glyph("🍒", "Cherry"),
        Glyph("🍇", "Grapes"), Glyph("🍉", "Watermelon"), Glyph("🍑", "Peach"), Glyph("🍍", "Pineapple"),
        Glyph("🥝", "Kiwi"), Glyph("🥥", "Coconut"), Glyph("🌽", "Corn"), Glyph("🥕", "Carrot"),
        Glyph("🍄", "Mushroom"), Glyph("🌶️", "Pepper"), Glyph("🍕", "Pizza"), Glyph("🍔", "Burger"),
        Glyph("🌮", "Taco"), Glyph("🍩", "Donut"), Glyph("🍪", "Cookie"), Glyph("🎂", "Cake"),
        Glyph("🍦", "Ice cream"), Glyph("🍿", "Popcorn"), Glyph("☕", "Coffee"), Glyph("🍵", "Tea"),
        Glyph("⚽", "Soccer"), Glyph("🏀", "Basketball"), Glyph("🏈", "Football"), Glyph("🎾", "Tennis"),
        Glyph("🎱", "8-ball"), Glyph("🎯", "Target"), Glyph("🎲", "Dice"), Glyph("🎮", "Game"),
        Glyph("🎸", "Guitar"), Glyph("🎺", "Trumpet"), Glyph("🎻", "Violin"), Glyph("🥁", "Drum"),
        Glyph("🎹", "Piano"), Glyph("🎤", "Mic"), Glyph("🎧", "Headphones"), Glyph("🚗", "Car"),
        Glyph("🚀", "Rocket"), Glyph("✈️", "Plane"), Glyph("🚲", "Bike"), Glyph("⛵", "Sailboat"),
        Glyph("🚁", "Helicopter"), Glyph("🚂", "Train"), Glyph("⚓", "Anchor"), Glyph("🪂", "Parachute"),
        Glyph("🌙", "Moon"), Glyph("⭐", "Star"), Glyph("☀️", "Sun"), Glyph("⚡", "Lightning"),
        Glyph("🔥", "Fire"), Glyph("❄️", "Snowflake"), Glyph("🌈", "Rainbow"), Glyph("🌻", "Sunflower"),
        Glyph("🌹", "Rose"), Glyph("🌵", "Cactus"), Glyph("🌲", "Tree"), Glyph("🍀", "Clover"),
        Glyph("💎", "Diamond"), Glyph("🔑", "Key"), Glyph("🔔", "Bell"), Glyph("🎁", "Gift"),
    )

    fun glyphs(sas: String): List<Glyph> {
        val v = (sas.toIntOrNull() ?: 0).let { if (it < 0) 0 else it } % 1_000_000
        val a = (v / 10_000) % 100
        val b = (v / 100) % 100
        val c = v % 100
        return listOf(TABLE[a], TABLE[b], TABLE[c])
    }
}
