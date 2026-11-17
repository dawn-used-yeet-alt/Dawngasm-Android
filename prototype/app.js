/*
 * Static prototype interactions: screen switching and library tabs.
 * All icon paths are the Material icons bundled with the Compose desktop app
 * (24x24 viewBox, filled paths).
 */

const ICONS = {
  search: "M15.5 14 h-0.79 l-0.28 -0.27 c1.2 -1.4 1.82 -3.31 1.48 -5.34 c-0.47 -2.78 -2.79 -5 -5.59 -5.34 c-4.23 -0.52 -7.79 3.04 -7.27 7.27 c0.34 2.8 2.56 5.12 5.34 5.59 c2.03 0.34 3.94 -0.28 5.34 -1.48 l0.27 0.28 v0.79 l4.25 4.25 c0.41 0.41 1.08 0.41 1.49 0 c0.41 -0.41 0.41 -1.08 0 -1.49 L15.5 14 Z M9.5 14 C7.01 14 5 11.99 5 9.5 S7.01 5 9.5 5 S14 7.01 14 9.5 S11.99 14 9.5 14 Z",
  history: "M13 3 c-4.97 0 -9 4.03 -9 9 L1 12 l3.89 3.89 l0.07 0.14 L9 12 L6 12 c0 -3.87 3.13 -7 7 -7 s7 3.13 7 7 s-3.13 7 -7 7 c-1.93 0 -3.68 -0.79 -4.94 -2.06 l-1.42 1.42 C8.27 19.99 10.51 21 13 21 c4.97 0 9 -4.03 9 -9 s-4.03 -9 -9 -9 Z M12 8 v5 l4.25 2.52 l0.77 -1.28 l-3.52 -2.09 L13.5 8 Z",
  heart: "M16.5 3 c-1.74 0 -3.41 0.81 -4.5 2.09 C10.91 3.81 9.24 3 7.5 3 C4.42 3 2 5.42 2 8.5 c0 3.78 3.4 6.86 8.55 11.54 L12 21.35 l1.45 -1.32 C18.6 15.36 22 12.28 22 8.5 C22 5.42 19.58 3 16.5 3 Z M12.1 18.55 l-0.1 0.1 l-0.1 -0.1 C7.14 14.24 4 11.39 4 8.5 C4 6.5 5.5 5 7.5 5 c1.54 0 3.04 0.99 3.57 2.36 h1.87 C13.46 5.99 14.96 5 16.5 5 c2 0 3.5 1.5 3.5 3.5 c0 2.89 -3.14 5.74 -7.9 10.05 Z",
  heartFilled: "M12 21.35 l-1.45 -1.32 C5.4 15.36 2 12.28 2 8.5 C2 5.42 4.42 3 7.5 3 c1.74 0 3.41 0.81 4.5 2.09 C13.09 3.81 14.76 3 16.5 3 C19.58 3 22 5.42 22 8.5 c0 3.78 -3.4 6.86 -8.55 11.54 L12 21.35 Z",
  download: "M19 9 h-4 L15 3 L9 3 v6 L5 9 l7 7 l7 -7 Z M11 11 L11 5 h2 v6 h1.17 L12 13.17 L9.83 11 L11 11 Z M5 18 h14 v2 L5 20 Z",
  downloadDone: "M5 18 h14 v2 L5 20 v-2 Z M9.6 15.3 L5 10.7 l2 -1.9 l2.6 2.6 L17 4 l2 2 l-9.4 9.3 Z",
  home: "M12 5.69 l5 4.5 V18 h-2 v-6 H9 v6 H7 v-7.81 l5 -4.5 M12 3 L2 12 h3 v8 h6 v-6 h2 v6 h6 v-8 h3 L12 3 Z",
  homeFilled: "M10 20 v-6 h4 v6 h5 v-8 h3 L12 3 L2 12 h3 v8 Z",
  library: "M20 2 L8 2 c-1.1 0 -2 0.9 -2 2 v12 c0 1.1 0.9 2 2 2 h12 c1.1 0 2 -0.9 2 -2 L22 4 c0 -1.1 -0.9 -2 -2 -2 Z M20 16 L8 16 L8 4 h12 v12 Z M12.5 15 c1.38 0 2.5 -1.12 2.5 -2.5 L15 7 h3 L18 5 h-4 v5.51 c-0.42 -0.32 -0.93 -0.51 -1.5 -0.51 c-1.38 0 -2.5 1.12 -2.5 2.5 s1.12 2.5 2.5 2.5 Z M4 6 L2 6 v14 c0 1.1 0.9 2 2 2 h14 v-2 L4 20 L4 6 Z",
  libraryFilled: "M20 2 L8 2 c-1.1 0 -2 0.9 -2 2 v12 c0 1.1 0.9 2 2 2 h12 c1.1 0 2 -0.9 2 -2 L22 4 c0 -1.1 -0.9 -2 -2 -2 Z M18 7 h-3 v5.5 c0 1.38 -1.12 2.5 -2.5 2.5 S10 13.88 10 12.5 s1.12 -2.5 2.5 -2.5 c0.57 0 1.08 0.19 1.5 0.51 L14 5 h4 v2 Z M4 6 L2 6 v14 c0 1.1 0.9 2 2 2 h14 v-2 L4 20 L4 6 Z",
  settings: "M19.43 12.98 c0.04 -0.32 0.07 -0.64 0.07 -0.98 c0 -0.34 -0.03 -0.66 -0.07 -0.98 l2.11 -1.65 c0.19 -0.15 0.24 -0.42 0.12 -0.64 l-2 -3.46 c-0.09 -0.16 -0.26 -0.25 -0.44 -0.25 c-0.06 0 -0.12 0.01 -0.17 0.03 l-2.49 1 c-0.52 -0.4 -1.08 -0.73 -1.69 -0.98 l-0.38 -2.65 C14.46 2.18 14.25 2 14 2 h-4 c-0.25 0 -0.46 0.18 -0.49 0.42 l-0.38 2.65 c-0.61 0.25 -1.17 0.59 -1.69 0.98 l-2.49 -1 c-0.06 -0.02 -0.12 -0.03 -0.18 -0.03 c-0.17 0 -0.34 0.09 -0.43 0.25 l-2 3.46 c-0.13 0.22 -0.07 0.49 0.12 0.64 l2.11 1.65 c-0.04 0.32 -0.07 0.65 -0.07 0.98 c0 0.33 0.03 0.66 0.07 0.98 l-2.11 1.65 c-0.19 0.15 -0.24 0.42 -0.12 0.64 l2 3.46 c0.09 0.16 0.26 0.25 0.44 0.25 c0.06 0 0.12 -0.01 0.17 -0.03 l2.49 -1 c0.52 0.4 1.08 0.73 1.69 0.98 l0.38 2.65 c0.03 0.24 0.24 0.42 0.49 0.42 h4 c0.25 0 0.46 -0.18 0.49 -0.42 l0.38 -2.65 c0.61 -0.25 1.17 -0.59 1.69 -0.98 l2.49 1 c0.06 0.02 0.12 0.03 0.18 0.03 c0.17 0 0.34 -0.09 0.43 -0.25 l2 -3.46 c0.12 -0.22 0.07 -0.49 -0.12 -0.64 l-2.11 -1.65 Z M17.45 11.27 c0.04 0.31 0.05 0.52 0.05 0.73 c0 0.21 -0.02 0.43 -0.05 0.73 l-0.14 1.13 l0.89 0.7 l1.08 0.84 l-0.7 1.21 l-1.27 -0.51 l-1.04 -0.42 l-0.9 0.68 c-0.43 0.32 -0.84 0.56 -1.25 0.73 l-1.06 0.43 l-0.16 1.13 l-0.2 1.35 h-1.4 l-0.19 -1.35 l-0.16 -1.13 l-1.06 -0.43 c-0.43 -0.18 -0.83 -0.41 -1.23 -0.71 l-0.91 -0.7 l-1.06 0.43 l-1.27 0.51 l-0.7 -1.21 l1.08 -0.84 l0.89 -0.7 l-0.14 -1.13 c-0.03 -0.31 -0.05 -0.54 -0.05 -0.74 s0.02 -0.43 0.05 -0.73 l0.14 -1.13 l-0.89 -0.7 l-1.08 -0.84 l0.7 -1.21 l1.27 0.51 l1.04 0.42 l0.9 -0.68 c0.43 -0.32 0.84 -0.56 1.25 -0.73 l1.06 -0.43 l0.16 -1.13 l0.2 -1.35 h1.39 l0.19 1.35 l0.16 1.13 l1.06 0.43 c0.43 0.18 0.83 0.41 1.23 0.71 l0.91 0.7 l1.06 -0.43 l1.27 -0.51 l0.7 1.21 l-1.07 0.85 l-0.89 0.7 l0.14 1.13 Z M12 8 c-2.21 0 -4 1.79 -4 4 s1.79 4 4 4 s4 -1.79 4 -4 s-1.79 -4 -4 -4 Z M12 14 c-1.1 0 -2 -0.9 -2 -2 s0.9 -2 2 -2 s2 0.9 2 2 s-0.9 2 -2 2 Z",
  settingsFilled: "M19.14 12.94 c0.04 -0.3 0.06 -0.61 0.06 -0.94 c0 -0.32 -0.02 -0.64 -0.07 -0.94 l2.03 -1.58 c0.18 -0.14 0.23 -0.41 0.12 -0.61 l-1.92 -3.32 c-0.12 -0.22 -0.37 -0.29 -0.59 -0.22 l-2.39 0.96 c-0.5 -0.38 -1.03 -0.7 -1.62 -0.94 L14.4 2.81 c-0.04 -0.24 -0.24 -0.41 -0.48 -0.41 h-3.84 c-0.24 0 -0.43 0.17 -0.47 0.41 L9.25 5.35 C8.66 5.59 8.12 5.92 7.63 6.29 L5.24 5.33 c-0.22 -0.08 -0.47 0 -0.59 0.22 L2.74 8.87 C2.62 9.08 2.66 9.34 2.86 9.48 l2.03 1.58 C4.84 11.36 4.8 11.69 4.8 12 s0.02 0.64 0.07 0.94 l-2.03 1.58 c-0.18 0.14 -0.23 0.41 -0.12 0.61 l1.92 3.32 c0.12 0.22 0.37 0.29 0.59 0.22 l2.39 -0.96 c0.5 0.38 1.03 0.7 1.62 0.94 l0.36 2.54 c0.05 0.24 0.24 0.41 0.48 0.41 h3.84 c0.24 0 0.44 -0.17 0.47 -0.41 l0.36 -2.54 c0.59 -0.24 1.13 -0.56 1.62 -0.94 l2.39 0.96 c0.22 0.08 0.47 0 0.59 -0.22 l1.92 -3.32 c0.12 -0.22 0.07 -0.47 -0.12 -0.61 L19.14 12.94 Z M12 15.6 c-1.98 0 -3.6 -1.62 -3.6 -3.6 s1.62 -3.6 3.6 -3.6 s3.6 1.62 3.6 3.6 S13.98 15.6 12 15.6 Z",
  tag: "M20 10 V8 h-4 V4 h-2 v4 h-4 V4 H8 v4 H4 v2 h4 v4 H4 v2 h4 v4 h2 v-4 h4 v4 h2 v-4 h4 v-2 h-4 v-4 H20 Z M14 14 h-4 v-4 h4 V14 Z",
  folder: "M20 6 h-8 l-2 -2 L4 4 c-1.1 0 -1.99 0.9 -1.99 2 L2 18 c0 1.1 0.9 2 2 2 h16 c1.1 0 2 -0.9 2 -2 L22 8 c0 -1.1 -0.9 -2 -2 -2 Z M20 18 L4 18 L4 8 h16 v10 Z",
  trash: "M6 19 c0 1.1 0.9 2 2 2 h8 c1.1 0 2 -0.9 2 -2 L18 7 L6 7 v12 Z M8 9 h8 v10 L8 19 L8 9 Z M15.5 4 l-1 -1 h-5 l-1 1 L5 4 v2 h14 L19 4 h-3.5 Z",
  close: "M19 6.41 L17.59 5 L12 10.59 L6.41 5 L5 6.41 L10.59 12 L5 17.59 L6.41 19 L12 13.41 L17.59 19 L19 17.59 L13.41 12 L19 6.41 Z",
  closeRounded: "M18.3 5.71 c-0.39 -0.39 -1.02 -0.39 -1.41 0 L12 10.59 L7.11 5.7 c-0.39 -0.39 -1.02 -0.39 -1.41 0 c-0.39 0.39 -0.39 1.02 0 1.41 L10.59 12 L5.7 16.89 c-0.39 0.39 -0.39 1.02 0 1.41 c0.39 0.39 1.02 0.39 1.41 0 L12 13.41 l4.89 4.89 c0.39 0.39 1.02 0.39 1.41 0 c0.39 -0.39 0.39 -1.02 0 -1.41 L13.41 12 l4.89 -4.89 c0.38 -0.38 0.38 -1.02 0 -1.4 Z",
  add: "M18 13 h-5 v5 c0 0.55 -0.45 1 -1 1 s-1 -0.45 -1 -1 v-5 H6 c-0.55 0 -1 -0.45 -1 -1 s0.45 -1 1 -1 h5 V6 c0 -0.55 0.45 -1 1 -1 s1 0.45 1 1 v5 h5 c0.55 0 1 0.45 1 1 s-0.45 1 -1 1 Z",
  sort: "M4 18 h4 c0.55 0 1 -0.45 1 -1 s-0.45 -1 -1 -1 L4 16 c-0.55 0 -1 0.45 -1 1 s0.45 1 1 1 Z M3 7 c0 0.55 0.45 1 1 1 h16 c0.55 0 1 -0.45 1 -1 s-0.45 -1 -1 -1 L4 6 c-0.55 0 -1 0.45 -1 1 Z M4 13 h10 c0.55 0 1 -0.45 1 -1 s-0.45 -1 -1 -1 L4 11 c-0.55 0 -1 0.45 -1 1 s0.45 1 1 1 Z",
  clock: "M11.99 2 C6.47 2 2 6.48 2 12 s4.47 10 9.99 10 C17.52 22 22 17.52 22 12 S17.52 2 11.99 2 Z M12 20 c-4.42 0 -8 -3.58 -8 -8 s3.58 -8 8 -8 s8 3.58 8 8 s-3.58 8 -8 8 Z M11.78 7 h-0.06 c-0.4 0 -0.72 0.32 -0.72 0.72 v4.72 c0 0.35 0.18 0.68 0.49 0.86 l4.15 2.49 c0.34 0.2 0.78 0.1 0.98 -0.24 c0.21 -0.34 0.1 -0.79 -0.25 -0.99 l-3.87 -2.3 L12.5 7.72 c0 -0.4 -0.32 -0.72 -0.72 -0.72 Z",
  hosts: "M8 18 c0.55 0 1 -0.45 1 -1 L9 7 c0 -0.55 -0.45 -1 -1 -1 s-1 0.45 -1 1 v10 c0 0.55 0.45 1 1 1 Z M12 22 c0.55 0 1 -0.45 1 -1 L13 3 c0 -0.55 -0.45 -1 -1 -1 s-1 0.45 -1 1 v18 c0 0.55 0.45 1 1 1 Z M4 14 c0.55 0 1 -0.45 1 -1 v-2 c0 -0.55 -0.45 -1 -1 -1 s-1 0.45 -1 1 v2 c0 0.55 0.45 1 1 1 Z M16 18 c0.55 0 1 -0.45 1 -1 L17 7 c0 -0.55 -0.45 -1 -1 -1 s-1 0.45 -1 1 v10 c0 0.55 0.45 1 1 1 Z M19 11 v2 c0 0.55 0.45 1 1 1 s1 -0.45 1 -1 v-2 c0 -0.55 -0.45 -1 -1 -1 s-1 0.45 -1 1 Z",
  chevron: "M7 10 l5 5 l5 -5 Z",
};

const FEED = [
  {
    title: "[F4M] Big Sis Lets You In Her Tent [Fdom] [Incest] [Cuddling] [Kissing] [Sleepy] [Slippery Slope] [Comfort] [ASMR] [Whispering] [Cock Kissing] [Blowjob] [Deepthroat] [Good Boys] [Cum Swallowing] [Cowgirl] [Doggy] [Mutual Orgasm] [Hold The Moan] [Creampie] [Virgin Listener] [Camping] [Cuddles]",
    meta: "Ivy_WildeVA · 5y ago · 24.6k",
  },
  {
    title: "[F4M] The Taken Girl in the Next Dorm Room Over [Cuckold] [Brat] to [Cockslut] [Mindbreak] [College] [Screaming Orgasms] [Let’s Make a Bet] [Blowjob] [Facefuck] [Rough Sex] [DoggyStyle] [Virgin Analpie] [Spanking] [Big Cock Worship] [Cheating on Her Boyfriend] [Daddy] [Humiliation] [Creampie]",
    meta: "beegeewanders · 4y ago · 22.0k",
  },
  {
    title: "[F4M] Your Best Friend Finds Out You're Submissive [FDom][Friends to Lovers][Making You Be Nice to Yourself][Encouragement][Pet Names][Call Me Mistress][Cunnilingus][Pushing You Against the Wall][Groping You][Missionary][Topping from the Bottom][Don't You Dare Pull Out][Cum Inside]",
    meta: "LookingForMyBlueSky · 5y ago · 17.0k",
  },
  {
    title: "[FFFFFFFFFFFFFF4M] The Yearbook [Pick Your Uni Type] [CYOA] [18+] [College] [Fdom] [Fsub] [MommyDom] [Tomboy] [Goth] [Bimbo] [Petplay] [Blackmail] [Yandere] [Bilingual] [Rape] [Good Boy] [Rough Sex] [Cowgirl] [Amazon] [Free-Use] [Handjob] [Blowjobs] [Doggy] [Anal] [Breeding] [Creampie]",
    meta: "Audio_Allure · 3y ago · 15.9k",
  },
  {
    title: "[FF4M][Script Fill] Quarantined with Your Gay Roommate and Her Bi Girlfriend [Threesome][Experimenting][First Cock][Tomboy][Slutty][Some Real Dirty Talk][Swearing][Cuckquean][Rough Sex][Some F/F][caught in the act][deepthroat][doggy girl][oral][spanking][virgin][wet sounds][cream][creampie] [Aftercare]",
    meta: "beegeewanders · 5y ago · 14.7k",
  },
  {
    title: "[F4M] Dude, Did You Seriously Get A Boner From Me Sitting On Your Lap? [Fsub] [Shortstack Girl] [Tomboy] [Friends to Lovers] [Size Difference] [Thigh Highs] [FLAT IS JUSTICE] [Mind Break] [Neck Kissing] [Lapsitting] [Thigh Job] [Carryfucking] [Pronebone] [Don't Pull Out] [Squirting On You]",
    meta: "alekiser · 2y ago · 14.3k",
  },
  {
    title: "[FFF4M] You're the Only Boy at the Sleepover... [Script Fill] [18+] [Friends to Lovers] [FDom] [FSub] [Virgin] [Teaching the Virgin] [Blowjob] [Cowgirl] [Group Sex] [Kissing] [Wet Pussy] [Fingering] [Creampie] [LOTS of Orgasms] featuring u/LookingForMyBlueSky and u/Xinley!!!",
    meta: "cyclosporineA · 6y ago · 13.8k",
  },
];

const BUBBLESY = {
  title: "[FF4M]Waking Up In a🎪🤹‍♂️🔥 CATGIRL Hospital💦[Script Fill][MEOW-Gasms][Doctor and Nurse][Threesome][Big Cock][Fighting]over[First Human] [Pheromones][Catgirls][Purrs][Furry][Monstergirls][Scent Kink][PIVs][Double Blowjob][Cock Cleaning][Tails and Ears]are[Sensitive]❤️‍🔥Lurkydip",
  meta: "BubblesyPressure · 2mo ago · 904",
  downloaded: true,
};

const HISTORY = [
  {
    title: "[F4M] Your Sister's Goth Bestie Sneaks Into Your Bed And Jacks You Off Because You're Too Cute Not To [Script Fill] [Soft FDom] [Unreachable Crush To Fuckbuddies] [Reacharound Handjob] [Milking My Little Bunny] [Hand-Pumping You To Our Fantasies] [Squeezing You Dry] [Tasting Your Spunk]",
    meta: "CocoKymmieVA · 12mo ago · 862",
  },
  BUBBLESY,
  {
    title: "[F4M] 😺😺🫶💋 Corrupted by Your Hot, MILF, Bad Auntie [Script Fill] [Older Woman x Younger Man] [MILF] [Incest] [Aunt x Nephew] [Huge Tits] [Thick Thighs] [Fat Ass] [Mature Pussy] [Rough Sex] [Blowjob] [Cock Worship] [Throatfuck] [No Condoms] [Mating Press] [DoggyStyle] [Creampie] [Corruption] [Aftercare] [Breeding]",
    meta: "lealealea305 · 6mo ago · 956",
  },
  {
    title: "[F4M] Your Catgirl Girlfriend Really, Really Misses You [Script Fill] [GFE] [Neko] [Catgirl] [In Heat] [Slopry] [Blowjob] [Deepthroat] [Doggy Style] [tail pulls you closer] [dripping wet] [Passionate sex] [grab my hips] [go deeper] [Babe] [\"Master\"] [Mutual Orgasm] [Creampie] [Dayz purrs][Meow-gasm]",
    meta: "Daisy4y4D4y · 4mo ago · 1.2k",
  },
  {
    title: "[F4M] Your Sister's Goth Bestie Sneaks Into Your Bed And Jacks You Off Because You're Too Cute Not To. 🖤 [Soft FDom] [Unreachable Crush To Fuckbuddies] [Reacharound Handjob] [Milking My Little Bunny] [Hand-Pumping You To Our Fantasies] [Squeezing You Dry] [Tasting Your Spunk] [Script Fill] [Rain] [Comfort]",
    meta: "nyaughtywooww · 7mo ago · 1.3k",
  },
  {
    title: "[F4M] Your Sister's Goth Bestie Sneaks Into Your Bed And Jacks You Off Because You're Too Cute Not To 🖤 [Script Fill] [Soft FDom] [Unreachable Crush To Fuckbuddies] [Reacharound Handjob] [Milking My Little Bunny] [Hand-Pumping You To Our Fantasies] [Squeezing You Dry] [Tasting Your Spunk] [Rain]",
    meta: "LeyLeyVA · 1y ago · 3.0k",
  },
  {
    title: "[M4F] Just a little bit of breeding should be fine, right? [Catboy] [BFE] [Cosy] [Gentle] [Playful] [Breeding] for the [First time] [Creampie] [Wet sounds] [Distraction] [Needy] [Please] [Meows] [Purring] [Petting] [Kisses]",
    meta: "Lustful_Crunch · 5y ago · 2.9k",
  },
];

const SAVED = [BUBBLESY];
const DOWNLOADS = [BUBBLESY];

const BLOCKED_TAGS = [
  "m4f",
  "m4a",
  "m4",
  "m4m",
  "m4tm",
  "m4tf",
  "m4nb",
  "mm4f",
  "m4m/tm",
  "m4m/a",
  "mm4m",
  "mm4a",
  "mm4tm",
];

const LIBRARY_DATA = {
  history: HISTORY,
  saved: SAVED,
  downloads: DOWNLOADS,
};

const libraryTabOrder = ["history", "saved", "downloads"];

const esc = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (char) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;",
      })[char],
  );

function iconMarkup(name, extraClass = "") {
  return `<svg class="icon ${extraClass}" viewBox="0 0 24 24" aria-hidden="true"><path d="${ICONS[name]}"/></svg>`;
}

function setIcon(container, name) {
  const icon = container.querySelector(".icon");
  if (icon) icon.outerHTML = iconMarkup(name);
}

function postMarkup(post, showSave) {
  const saved = post.saved === true;
  const downloaded = post.downloaded === true;
  const saveButton = showSave
    ? `<button class="icon-button icon-button--save${saved ? " is-saved" : ""}" type="button"
         aria-pressed="${saved}" aria-label="${saved ? "Unsave" : "Save"} track" title="${saved ? "Unsave" : "Save"}">
         ${iconMarkup(saved ? "heartFilled" : "heart")}
       </button>`
    : "";
  return `<article class="post">
    <div class="post__body">
      <h2 class="post__title">${esc(post.title)}</h2>
      <p class="post__meta">${esc(post.meta)}</p>
    </div>
    <div class="post__actions${showSave ? "" : " post__actions--single"}">
      ${saveButton}
      <button class="icon-button icon-button--download${downloaded ? " is-downloaded" : ""}" type="button"
        aria-pressed="${downloaded}" aria-label="${downloaded ? "Downloaded" : "Download"} track"
        title="${downloaded ? "Downloaded" : "Download"}">
        ${iconMarkup(downloaded ? "downloadDone" : "download")}
      </button>
    </div>
  </article>`;
}

function renderList(list, posts, showSave) {
  list.innerHTML = posts.map((post) => postMarkup(post, showSave)).join("");
}

function bindToggles(list) {
  list.addEventListener("click", (event) => {
    const saveButton = event.target.closest(".icon-button--save");
    if (saveButton) {
      const saved = saveButton.getAttribute("aria-pressed") === "true";
      saveButton.setAttribute("aria-pressed", String(!saved));
      saveButton.classList.toggle("is-saved", !saved);
      saveButton.setAttribute("aria-label", saved ? "Save track" : "Unsave track");
      saveButton.title = saved ? "Save" : "Unsave";
      setIcon(saveButton, saved ? "heart" : "heartFilled");
      return;
    }

    const downloadButton = event.target.closest(".icon-button--download");
    if (downloadButton) {
      const downloaded = downloadButton.getAttribute("aria-pressed") === "true";
      downloadButton.setAttribute("aria-pressed", String(!downloaded));
      downloadButton.classList.toggle("is-downloaded", !downloaded);
      downloadButton.setAttribute("aria-label", downloaded ? "Download track" : "Downloaded");
      downloadButton.title = downloaded ? "Download" : "Downloaded";
      setIcon(downloadButton, downloaded ? "download" : "downloadDone");
    }
  });
}

/* ---------- Navigation ---------- */

const screens = [...document.querySelectorAll(".screen")];
const railItems = [...document.querySelectorAll(".rail-item")];
const railIconNames = {
  home: { active: "homeFilled", inactive: "home" },
  library: { active: "libraryFilled", inactive: "library" },
  settings: { active: "settingsFilled", inactive: "settings" },
};

let activeScreen = "home";
let activeLibraryTab = "history";

function showScreen(name) {
  activeScreen = name;
  screens.forEach((screen) => {
    screen.hidden = screen.id !== `screen-${name}`;
  });
  railItems.forEach((item) => {
    const selected = item.dataset.nav === name;
    item.classList.toggle("is-active", selected);
    if (selected) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
    setIcon(
      item.querySelector(".rail-item__indicator"),
      selected ? railIconNames[item.dataset.nav].active : railIconNames[item.dataset.nav].inactive,
    );
  });
  if (name === "library") renderLibrary();
}

/* ---------- Library tabs ---------- */

const tabIndicator = document.querySelector(".pill-tabs__indicator");
const tabButtons = [...document.querySelectorAll(".pill-tab")];
const libraryList = document.getElementById("library-list");

function updateTabIcons() {
  const historyTab = tabButtons.find((tab) => tab.dataset.tab === "history");
  const savedTab = tabButtons.find((tab) => tab.dataset.tab === "saved");
  const downloadsTab = tabButtons.find((tab) => tab.dataset.tab === "downloads");
  setIcon(historyTab, "history");
  setIcon(savedTab, activeLibraryTab === "saved" ? "heartFilled" : "heart");
  setIcon(downloadsTab, activeLibraryTab === "downloads" ? "downloadDone" : "download");
}

function selectLibraryTab(tab) {
  activeLibraryTab = tab;
  const index = libraryTabOrder.indexOf(tab);
  tabButtons.forEach((button) => {
    button.setAttribute("aria-selected", String(button.dataset.tab === tab));
  });
  tabIndicator.style.transform = `translateX(${index * 100}%)`;
  updateTabIcons();
  renderLibrary();
}

function renderLibrary() {
  renderList(libraryList, LIBRARY_DATA[activeLibraryTab], false);
  libraryList.scrollTop = 0;
}

/* ---------- Boot ---------- */

document.querySelectorAll("[data-icon]").forEach((placeholder) => {
  placeholder.outerHTML = iconMarkup(placeholder.dataset.icon);
});

document.getElementById("blocked-tags").innerHTML = BLOCKED_TAGS.map(
  (tag) => `<button class="chip" type="button" aria-label="Remove tag ${esc(tag)}">
      ${esc(tag)}
      <span aria-hidden="true">${iconMarkup("close")}</span>
    </button>`,
).join("");

renderList(document.getElementById("home-list"), FEED, true);
bindToggles(document.getElementById("home-list"));

bindToggles(libraryList);

railItems.forEach((item) => {
  item.addEventListener("click", () => showScreen(item.dataset.nav));
});

tabButtons.forEach((button) => {
  button.addEventListener("click", () => selectLibraryTab(button.dataset.tab));
});

showScreen("home");
selectLibraryTab("history");

/* ---------- Search sheet ---------- */
/*
 * Mirrors SearchSheet.kt: a modal sheet with a text field, tag pickers with
 * suggestion pills, sort/window/host dropdowns, an applied-query preview and
 * a filled Search action.
 */

const SORT_OPTIONS = [
  { value: "top", label: "Top" },
  { value: "new", label: "New" },
  { value: "relevance", label: "Relevance" },
];

const WINDOW_OPTIONS = [
  { value: "day", label: "Past day" },
  { value: "week", label: "Past week" },
  { value: "month", label: "Past month" },
  { value: "year", label: "Past year" },
  { value: "all", label: "All time" },
];

const HOST_OPTIONS = [
  { value: "all", label: "All hosts" },
  { value: "soundgasm", label: "Soundgasm" },
  { value: "whyp", label: "Whyp" },
  { value: "audiochan", label: "Audiochan" },
  { value: "hotaudio", label: "Hotaudio" },
];

const TAG_LOOKUP = [
  { label: "f4m", count: 48213 },
  { label: "m4f", count: 32104 },
  { label: "script fill", count: 21460 },
  { label: "fdom", count: 18207 },
  { label: "fsub", count: 12610 },
  { label: "m4m", count: 9840 },
  { label: "creampie", count: 9128 },
  { label: "blowjob", count: 8704 },
  { label: "asmr", count: 7382 },
  { label: "f4f", count: 6405 },
  { label: "gfe", count: 6218 },
  { label: "bfe", count: 5487 },
  { label: "ff4m", count: 5102 },
  { label: "tomboy", count: 3810 },
  { label: "catgirl", count: 2415 },
  { label: "mm4f", count: 2206 },
  { label: "incest", count: 2130 },
  { label: "aftercare", count: 1904 },
  { label: "sleep aid", count: 1633 },
  { label: "mommydom", count: 1487 },
];

const AUTHOR_LOOKUP = [
  { label: "Ivy_WildeVA", count: 24600 },
  { label: "beegeewanders", count: 22000 },
  { label: "Audio_Allure", count: 15900 },
  { label: "alekiser", count: 14300 },
  { label: "LookingForMyBlueSky", count: 17000 },
  { label: "Lustful_Crunch", count: 2900 },
  { label: "Daisy4y4D4y", count: 1200 },
  { label: "BubblesyPressure", count: 904 },
  { label: "CocoKymmieVA", count: 862 },
];

const searchLayer = document.getElementById("search-layer");
const searchRow = document.getElementById("search-row");
const searchRowText = searchRow.querySelector(".searchbar__text");
const searchRowClear = document.getElementById("search-row-clear");
const searchText = document.getElementById("search-text");
const searchTextClear = document.getElementById("search-text-clear");
const suggestionsEl = document.getElementById("search-suggestions");
const previewEl = document.getElementById("search-preview");
const previewText = document.getElementById("search-preview-text");
const menuEl = document.getElementById("search-menu");

const searchState = {
  sort: "top",
  window: "all",
  host: "all",
};

const tagFields = {};
let activeTagField = null;
let menuOwner = null;

/* -- helpers -- */

const compact = (value) =>
  value >= 1000 ? `${(value / 1000).toFixed(1).replace(/\.0$/, "")}k` : String(value);

function normalizeTag(raw) {
  let tag = String(raw).trim().replace(/^-/, "").trim();
  if (tag.length >= 2 && tag.startsWith("[") && tag.endsWith("]")) {
    tag = tag.slice(1, -1).trim();
  }
  return tag.toLowerCase().replace(/\s+/g, " ").trim();
}

function parseTagList(csv) {
  return csv
    .split(",")
    .map(normalizeTag)
    .filter((tag, index, list) => tag && list.indexOf(tag) === index);
}

/* -- dropdowns -- */

function closeMenu() {
  if (!menuOwner) return;
  menuOwner.classList.remove("is-open");
  menuOwner = null;
  menuEl.hidden = true;
  menuEl.innerHTML = "";
}

function openMenu(dropdown, options, current, onSelect) {
  closeMenu();
  menuEl.innerHTML = options
    .map(
      (option) => `<button class="menu__item${option.value === current ? " is-selected" : ""}"
        type="button" role="option" aria-selected="${option.value === current}"
        data-value="${option.value}">${option.label}</button>`,
    )
    .join("");
  menuEl.hidden = false;
  dropdown.classList.add("is-open");
  menuOwner = dropdown;

  const rect = dropdown.getBoundingClientRect();
  const width = Math.max(rect.width, 200);
  menuEl.style.width = `${width}px`;
  menuEl.style.left = `${Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))}px`;
  const height = menuEl.offsetHeight;
  let top = rect.bottom + 6;
  if (top + height > window.innerHeight - 8) {
    top = Math.max(8, rect.top - height - 6);
  }
  menuEl.style.top = `${top}px`;

  menuEl.onclick = (event) => {
    const item = event.target.closest(".menu__item");
    if (!item) return;
    onSelect(item.dataset.value);
    closeMenu();
  };
}

const DROPDOWN_CONFIG = {
  sort: SORT_OPTIONS,
  window: WINDOW_OPTIONS,
  host: HOST_OPTIONS,
};

document.querySelectorAll("[data-dropdown]").forEach((dropdown) => {
  const kind = dropdown.dataset.dropdown;
  const valueEl = dropdown.querySelector(".dropdown__value");
  dropdown.addEventListener("click", (event) => {
    event.stopPropagation();
    if (menuOwner === dropdown) {
      closeMenu();
      return;
    }
    const options = DROPDOWN_CONFIG[kind];
    openMenu(dropdown, options, searchState[kind], (value) => {
      searchState[kind] = value;
      valueEl.textContent = options.find((option) => option.value === value).label;
      updatePreview();
    });
  });
});

document.addEventListener("click", (event) => {
  if (event.target.closest("#search-menu")) return;
  closeMenu();
});

/* -- tag fields -- */

function syncTagField(field) {
  field.classList.toggle("has-value", field.querySelector("input").value.trim() !== "");
}

function renderSuggestions(key) {
  if (!key) {
    suggestionsEl.hidden = true;
    suggestionsEl.innerHTML = "";
    return;
  }

  const input = tagFields[key].input;
  const segments = input.value.split(",");
  const draft = segments[segments.length - 1].trim();
  if (!draft) {
    suggestionsEl.hidden = true;
    suggestionsEl.innerHTML = "";
    return;
  }

  const prefix = draft.toLowerCase().replace(/^u\//, "");
  const chosen = new Set(
    segments
      .slice(0, -1)
      .map(normalizeTag)
      .filter(Boolean),
  );
  const source = key === "author" ? AUTHOR_LOOKUP : TAG_LOOKUP;
  const items = source
    .filter(
      (item) =>
        item.label.toLowerCase().startsWith(prefix) &&
        !chosen.has(item.label.toLowerCase()),
    )
    .slice(0, 6);

  if (!items.length) {
    suggestionsEl.hidden = true;
    suggestionsEl.innerHTML = "";
    return;
  }

  suggestionsEl.innerHTML = items
    .map(
      (item) => `<button class="suggestion" type="button" data-suggestion="${esc(item.label)}">
        ${iconMarkup("add")}
        ${esc(item.label)}
        <span class="suggestion__count">${compact(item.count)}</span>
      </button>`,
    )
    .join("");
  suggestionsEl.hidden = false;
}

document.querySelectorAll("[data-tag-field]").forEach((field) => {
  const key = field.dataset.tagField;
  const input = field.querySelector("input");
  tagFields[key] = { field, input };

  input.addEventListener("input", () => {
    syncTagField(field);
    activeTagField = key;
    renderSuggestions(key);
    updatePreview();
  });

  input.addEventListener("focus", () => {
    activeTagField = key;
    renderSuggestions(key);
  });

  input.addEventListener("blur", () => {
    window.setTimeout(() => {
      if (activeTagField === key && !field.contains(document.activeElement)) {
        activeTagField = null;
        renderSuggestions(null);
      }
    }, 120);
  });
});

suggestionsEl.addEventListener("mousedown", (event) => {
  const button = event.target.closest(".suggestion");
  if (!button || !activeTagField) return;
  event.preventDefault();

  const { field, input } = tagFields[activeTagField];
  const label = button.dataset.suggestion;
  const segments = input.value.split(",").map((segment) => segment.trim());
  segments[segments.length - 1] = label;
  input.value = `${segments.filter(Boolean).join(", ")}, `;
  syncTagField(field);
  input.focus();
  renderSuggestions(null);
  updatePreview();
});

/* -- preview -- */

function buildSummary() {
  const parts = [];
  const text = searchText.value.trim();
  if (text) parts.push(text);
  parseTagList(tagFields.include.input.value).forEach((tag) => parts.push(`[${tag}]`));
  parseTagList(tagFields.block.input.value).forEach((tag) => parts.push(`-[${tag}]`));
  parseTagList(tagFields.author.input.value).forEach((tag) => parts.push(`author:${tag}`));
  if (searchState.host !== "all") parts.push(`host:${searchState.host}`);
  return parts.join(" ");
}

function updatePreview() {
  const summary = buildSummary();
  previewText.textContent = summary;
  previewEl.hidden = summary === "";
  return summary;
}

function setSearchRowQuery(summary) {
  searchRow.classList.toggle("has-query", summary !== "");
  searchRowText.textContent = summary || "Search titles and tags";
  searchRowClear.hidden = summary === "";
}

function resetFilters() {
  searchText.value = "";
  searchTextClear.hidden = true;
  Object.values(tagFields).forEach(({ field, input }) => {
    input.value = "";
    field.classList.remove("has-value");
  });
  searchState.sort = "top";
  searchState.window = "all";
  searchState.host = "all";
  document.querySelectorAll("[data-dropdown]").forEach((dropdown) => {
    const kind = dropdown.dataset.dropdown;
    dropdown.querySelector(".dropdown__value").textContent = DROPDOWN_CONFIG[kind].find(
      (option) => option.value === searchState[kind],
    ).label;
  });
  suggestionsEl.hidden = true;
  updatePreview();
}

/* -- open / close -- */

function openSearch() {
  searchLayer.hidden = false;
  closeMenu();
}

function closeSearch() {
  searchLayer.hidden = true;
  closeMenu();
  activeTagField = null;
  suggestionsEl.hidden = true;
}

document.querySelectorAll("[data-search-dismiss]").forEach((element) => {
  element.addEventListener("click", closeSearch);
});

searchRow.addEventListener("click", openSearch);
searchRow.addEventListener("keydown", (event) => {
  if (event.key === "Enter" || event.key === " ") {
    event.preventDefault();
    openSearch();
  }
});

document.querySelector(".fab").addEventListener("click", openSearch);

searchRowClear.addEventListener("click", (event) => {
  event.stopPropagation();
  resetFilters();
  setSearchRowQuery("");
});

searchText.addEventListener("input", () => {
  searchTextClear.hidden = searchText.value === "";
  updatePreview();
});

searchTextClear.addEventListener("click", () => {
  searchText.value = "";
  searchTextClear.hidden = true;
  updatePreview();
  searchText.focus();
});

document.getElementById("search-clear-all").addEventListener("click", () => {
  resetFilters();
  searchText.focus();
});

document.getElementById("search-submit").addEventListener("click", () => {
  const summary = updatePreview();
  setSearchRowQuery(summary);
  closeSearch();
});

document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  if (!menuEl.hidden) {
    closeMenu();
    return;
  }
  if (!searchLayer.hidden) closeSearch();
});
