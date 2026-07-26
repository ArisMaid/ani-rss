import DOMPurify from 'dompurify'
import markdownit from 'markdown-it'
import MarkdownItGitHubAlerts from 'markdown-it-github-alerts'

const markdown = markdownit({
    html: false,
    linkify: true
})

markdown.renderer.rules.link_open = (tokens, index, options, environment, renderer) => {
    const token = tokens[index]
    token.attrSet('target', '_blank')
    token.attrSet('rel', 'noopener noreferrer')
    return renderer.renderToken(tokens, index, options, environment)
}

markdown.use(MarkdownItGitHubAlerts)

export const renderSafeMarkdown = value => DOMPurify.sanitize(
    markdown.render(value || ''),
    {ADD_ATTR: ['target', 'rel']}
)
