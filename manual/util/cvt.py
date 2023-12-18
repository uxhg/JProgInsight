import json

# Replace 'input_file.py' with the path to your source code file.
input_file_path = 'input'

# Read the source code from the file.
with open(input_file_path, 'r') as file:
    source_code = file.read()

# Encode the source code as a JSON string.
json_string = json.dumps(source_code)

# Replace 'output_file.json' with the desired output JSON file path.
output_file_path = 'output_file.json'

# Write the JSON string to a new file.
with open(output_file_path, 'w') as output_file:
    output_file.write(json_string)
