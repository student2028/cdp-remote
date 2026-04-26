// Function to copy text to clipboard
function copyCode(elementId) {
    const codeElement = document.getElementById(elementId);
    const textToCopy = codeElement.innerText;
    const btn = codeElement.closest('.code-block').querySelector('.copy-btn');

    navigator.clipboard.writeText(textToCopy).then(() => {
        const originalText = btn.innerText;
        btn.innerText = '已复制!';
        btn.classList.add('copied');
        
        setTimeout(() => {
            btn.innerText = originalText;
            btn.classList.remove('copied');
        }, 2000);
    }).catch(err => {
        console.error('Failed to copy text: ', err);
        btn.innerText = '失败';
        setTimeout(() => btn.innerText = '复制', 2000);
    });
}

// Intersection Observer for scroll animations
document.addEventListener('DOMContentLoaded', () => {
    const observerOptions = {
        root: null,
        rootMargin: '0px',
        threshold: 0.1
    };

    const observer = new IntersectionObserver((entries, observer) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                // If it's a fade-in-up element that hasn't been animated yet
                if (entry.target.classList.contains('fade-in-up') && !entry.target.style.animationName) {
                    // Force a reflow to restart animation if needed, though class setup handles it
                    entry.target.style.animationPlayState = 'running';
                }
                // Optional: Stop observing once animated
                // observer.unobserve(entry.target);
            }
        });
    }, observerOptions);

    // Initial setup: For elements below the fold, we could pause their animations
    // but the CSS already handles the initial state. 
    // This is just future-proofing if we add more complex scroll-trigger classes.
    const animatedElements = document.querySelectorAll('.fade-in-up, .fade-in');
    animatedElements.forEach(el => observer.observe(el));
});
