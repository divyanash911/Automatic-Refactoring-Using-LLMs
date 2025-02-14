#!/usr/bin/env python3
import os
import random
import datetime
from github import Github
import os
import google.generativeai as genai

genai.configure(api_key=os.environ["GOOGLE_KEY"])

# Create the model
generation_config = {
  "temperature": 1,
  "top_p": 0.95,
  "top_k": 40,
  "max_output_tokens": 8192,
  "response_mime_type": "text/plain",
}

model = genai.GenerativeModel(
  model_name="gemini-2.0-flash",
  generation_config=generation_config,
)

chat_session = model.start_chat(
  history=[
  ]
)

def call_llm(prompt: str, role: str) -> str:
    """
    Dummy function simulating an API call to an LLM.
    Replace this with your actual LLM integration.
    """
    print(f"[{role}] Prompt (first 100 chars): {prompt[:100]}...\n")
    
    response = chat_session.send_message(f"{role}: {prompt}")
    # Dummy response for demonstration purposes.
    return response.text

def get_repo() -> object:
    """
    Returns a PyGithub Repository object using the provided environment variables.
    """
    token = os.environ.get("TOKEN")
    repo_name = "divyanash911/project-1-team-14"
    if not token or not repo_name:
        raise Exception("TOKEN and REPOSITORY must be set as environment variables.")
    
    g = Github(token)
    repo = g.get_repo(repo_name)
    print(f"Connected to GitHub repository: {repo_name}")
    return repo

def pick_files(repo, branch: str = "main", count: int = 2) -> list:
    """
    Retrieves the repository's file tree (recursively) from the specified branch,
    filters for Python files, and randomly picks up to `count` files.
    """
    if branch is None:
        branch = repo.default_branch  # Use the default branch if none is provided
    
    tree = repo.get_git_tree(branch, recursive=True).tree
    py_files = [item.path for item in tree if item.type == "blob" and item.path.endswith(".java")]
    if not py_files:
        return []
    selected_files = random.sample(py_files, min(count, len(py_files)))
    print("Selected files for refactoring:")
    for f in selected_files:
        print(" -", f)
    return selected_files

def refactor_file(repo, file_path: str, branch: str = "main") -> (str, str):
    """
    Retrieves the file content from the repository, sends it to dummy LLM functions to detect design smells
    and generate a refactored version, and returns both the design smells summary and the new code.
    """
    content_file = repo.get_contents(file_path, ref=branch)
    original_code = content_file.decoded_content.decode('utf-8')
    
    prompt_design_smells = (
        f"Analyze the following code for design smells and list any issues found:\n\n"
        f"{original_code}\n\n"
        "Please provide a brief summary."
    )
    design_smells = call_llm(prompt_design_smells, role="Design Smell Finder")
    
    prompt_refactor = (
        f"Based on the following detected design smells:\n{design_smells}\n\n"
        f"Refactor the code below to address these issues and improve code quality. "
        f"Return only the complete new file content:\n\n{original_code}"
    )
    refactored_code = call_llm(prompt_refactor, role="Refactoring Expert")
    
    return design_smells, refactored_code

def apply_refactorings_to_files(repo, files_updates: dict) -> str:
    """
    Creates a new branch from the main branch, updates the specified files with the new content,
    and commits the changes using the GitHub API.
    
    :param files_updates: A dict mapping file paths to their new content.
    :return: The name of the branch that was created.
    """
    # Create a new branch name based on the current timestamp.
    branch_name = "llm-refactor"
    ref_name = "refs/heads/" + branch_name

    # Get the latest commit sha from the main branch.
    main_branch = repo.get_branch("main")
    base_sha = main_branch.commit.sha

    print(f"Creating new branch '{branch_name}' from 'main'...")
    repo.create_git_ref(ref=ref_name, sha=base_sha)

    commit_message = "Apply LLM-suggested refactorings for selected files"
    # Update each file with the refactored content.
    for file_path, new_content in files_updates.items():
        print(f"Updating file: {file_path} at repo: {repo.full_name}")
        # Retrieve the file from the new branch.
        print(new_content)
        
        file_obj = repo.get_contents(file_path, ref=branch_name)
        repo.update_file(
            path=file_path,
            message=commit_message,
            content=new_content,
            sha=file_obj.sha,
            branch=branch_name
        )
    return branch_name

def create_pull_request(repo, branch_name: str, pr_body: str) -> str:
    """
    Creates a pull request against the original repository if working on a fork.
    
    If the repo is a fork, it creates a PR on the upstream repository (repo.parent)
    using the fork's branch (with the head parameter formatted as "forkOwner:branchName").
    
    :param repo: The repository object (which may be your fork).
    :param branch_name: The branch name where the changes are pushed.
    :param pr_body: The pull request description.
    :return: The URL of the created pull request.
    """
    # If working on a fork, use the parent (upstream) repository for the pull request.
    if repo.fork and repo.parent:
        target_repo = repo.parent
        # The head branch must be specified in the format "username:branch".
        head_branch = f"{repo.owner.login}:{branch_name}"
    else:
        target_repo = repo
        head_branch = branch_name

    title = "LLM Refactoring: Automated Code Improvements"
    base_branch = "main"  # Change if your target branch is different
    print("Creating pull request on GitHub...")
    
    pr = target_repo.create_pull(title=title, body=pr_body, head=head_branch, base=base_branch)
    return pr.html_url


def main():
    try:
        # Connect to the repository using the GitHub API.
        repo = get_repo()
        
        # Retrieve a list of .py files and randomly select 1–2 files for processing.
        selected_files = pick_files(repo, branch="master", count=2)
        if not selected_files:
            print("No eligible files found for refactoring.")
            return
        
        # For each selected file, detect design smells and generate refactored code.
        files_design_smells = {}
        files_refactored = {}
        for file_path in selected_files:
            print(f"\nProcessing file: {file_path}")
            design_smells, refactored_code = refactor_file(repo, file_path, branch="main")
            files_design_smells[file_path] = design_smells
            files_refactored[file_path] = refactored_code
        
        # Create a new branch and update the files with the refactored code.
        branch_name = apply_refactorings_to_files(repo, files_refactored)
        
        # Construct the pull request body with summaries of the design smells.
        pr_body_lines = ["## LLM Refactoring Summary\n"]
        for file_path, design_smells in files_design_smells.items():
            pr_body_lines.append(f"### File: `{file_path}`")
            pr_body_lines.append("**Design Smells Detected:**")
            pr_body_lines.append(design_smells)
            pr_body_lines.append("\n")
        pr_body = "\n".join(pr_body_lines)
        
        # Create a pull request using the GitHub API.
        pr_url = create_pull_request(repo, branch_name, pr_body)
        print("\nPull Request created successfully:")
        print(pr_url)
    
    except Exception as e:
        print("An error occurred:", str(e))

if __name__ == "__main__":
    main()
