/**
 * /wm-keyboard-docs.zip — the whole documentation to keep (#970).
 *
 * Every page as the same Markdown its `.md` copy serves, in folders the way the
 * sidebar groups them, plus a README naming the version of the site it was
 * built from. For reading on a plane, or in a notes app, without the site.
 * Built with the rest of the site, so it is never older than the pages.
 */

import type { APIRoute } from 'astro';
import { getCollection } from 'astro:content';
import { strToU8, zipSync, type Zippable } from 'fflate';

import { orderedDocs, pageMarkdown } from '../lib/llm-text';
import { SITE_TITLE, SITE_URL } from '../site.mjs';

export const GET: APIRoute = async () => {
	const files: Zippable = {};
	const docs = orderedDocs(await getCollection('docs'));
	for (const doc of docs) {
		files[`wm-keyboard-docs/${doc.id || 'index'}.md`] = strToU8(pageMarkdown(doc));
	}
	const built = new Date().toISOString().slice(0, 10);
	files['wm-keyboard-docs/README.md'] = strToU8(
		[
			`# ${SITE_TITLE} documentation`,
			'',
			`All ${docs.length} pages of ${SITE_URL}/ as Markdown, built on ${built}.`,
			'Each file is one page; the folders follow the site\'s sections.',
			`A newer copy is always at ${SITE_URL}/wm-keyboard-docs.zip.`,
			'',
		].join('\n'),
	);
	return new Response(zipSync(files, { level: 9 }), {
		headers: {
			'Content-Type': 'application/zip',
			'Content-Disposition': 'attachment; filename="wm-keyboard-docs.zip"',
		},
	});
};
