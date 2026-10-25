// @ts-check
import { satteri } from '@astrojs/markdown-satteri';
import starlight from '@astrojs/starlight';
import { defineConfig } from 'astro/config';
import { join } from 'node:path';
import starlightLinksValidator from 'starlight-links-validator';
import { REPO_DIR, syncDocs } from './scripts/sync-docs.mjs';

// The repository's Markdown is the source; generate the content collection from it before Astro reads it.
const { pages } = await syncDocs();

// Once the beginner guide gains a "Coming from Cocoon" section, link it beside the Cocoon import page.
const guide = pages.find((p) => p.slug === 'getting-started');
const cocoonSection = guide?.headings.find((h) => h.depth === 2 && /coming from cocoon/i.test(h.text));

/** Re-runs the sync while `astro dev` is running, when the repository docs change. */
function watchRepositoryDocs() {
  const sources = ['docs', 'CHANGELOG.md', 'app/src/main/assets/MANUAL.md', 'branding'].map((p) => join(REPO_DIR, p));
  return {
    name: 'sortie-docs-sync',
    hooks: {
      /** @param {{ server: import('vite').ViteDevServer, logger: import('astro').AstroIntegrationLogger }} options */
      'astro:server:setup': ({ server, logger }) => {
        server.watcher.add(sources);
        /** @type {ReturnType<typeof setTimeout> | undefined} */
        let timer;
        server.watcher.on('all', (_event, file) => {
          if (!sources.some((s) => file.startsWith(s))) return;
          clearTimeout(timer);
          timer = setTimeout(() => {
            syncDocs().then(
              () => logger.info('repository docs re-synced'),
              (err) => logger.error(err.message),
            );
          }, 150);
        });
      },
    },
  };
}

export default defineConfig({
  site: 'https://sortie-launcher.github.io',
  base: '/sortie',
  // Render the docs as GitHub does: no curly quotes or dash substitution.
  markdown: { processor: satteri({ features: { smartPunctuation: false } }) },
  integrations: [
    watchRepositoryDocs(),
    starlight({
      title: 'Sortie',
      description: 'An offline, controller-first game launcher for Android handhelds.',
      logo: { src: './src/assets/brand/mark.svg', alt: '' },
      favicon: '/favicon.svg',
      social: [{ icon: 'github', label: 'Sortie on GitHub', href: 'https://github.com/sortie-launcher/sortie' }],
      customCss: [
        '@fontsource/lato/400.css',
        '@fontsource/lato/400-italic.css',
        '@fontsource/lato/700.css',
        '@fontsource/lato/900-italic.css',
        './src/styles/docs.css',
      ],
      components: {
        ThemeProvider: './src/components/ThemeProvider.astro',
        Footer: './src/components/DocsFooter.astro',
      },
      head: [
        { tag: 'meta', attrs: { property: 'og:image', content: 'https://sortie-launcher.github.io/sortie/social-preview.png' } },
        { tag: 'meta', attrs: { property: 'og:image:alt', content: 'Sortie: the amber Split-S mark' } },
        { tag: 'meta', attrs: { name: 'twitter:card', content: 'summary_large_image' } },
      ],
      sidebar: [
        {
          label: 'Start here',
          items: [
            { label: 'Getting started', slug: 'getting-started' },
            ...(cocoonSection ? [{ label: cocoonSection.text, link: `/getting-started/#${cocoonSection.slug}` }] : []),
            { label: 'Import from Cocoon', slug: 'cocoon-import' },
          ],
        },
        { label: 'Using Sortie', items: [{ label: 'Manual', slug: 'manual' }] },
        {
          label: 'Reference',
          items: [
            { label: 'File formats', items: [{ autogenerate: { directory: 'reference/formats' } }] },
            { label: 'Emulator launch compatibility', slug: 'reference/launchers' },
          ],
        },
        {
          label: 'Project',
          items: [
            { label: 'Releases and beta status', slug: 'project/release' },
            { label: 'Changelog', slug: 'project/changelog' },
            { label: 'Specification', slug: 'project/spec' },
            { label: 'Now playing (scope note)', slug: 'project/now-playing' },
            ...pages
              .filter((p) => p.provenance)
              .map((p) => ({
                label: p.slug === 'project/screenshots' ? 'Screenshot provenance' : p.title ?? p.slug,
                slug: p.slug,
              })),
          ],
        },
      ],
      plugins: [starlightLinksValidator({ errorOnInvalidHashes: true, errorOnLocalLinks: true })],
    }),
  ],
});
