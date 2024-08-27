import json

filePath = "./cities.json"

cityIdString = ""
with open(filePath) as json_file:
    data = json.load(json_file)
    for city in data:
        if city["country"] == "FI": 
            cityIdString += str(city["id"])
            cityIdString += ","

print(cityIdString)
